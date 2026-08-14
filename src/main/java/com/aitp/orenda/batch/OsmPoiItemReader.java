package com.aitp.orenda.batch;

import com.aitp.orenda.model.OsmPoi;
import de.topobyte.osm4j.core.access.OsmIterator;
import de.topobyte.osm4j.core.model.iface.*;
import de.topobyte.osm4j.pbf.seq.PbfIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

@Component
public class OsmPoiItemReader implements ItemStreamReader<OsmPoi> {

    private static final Logger log = LoggerFactory.getLogger(OsmPoiItemReader.class);

    private static final Set<String> POI_KEYS =
        Set.of("tourism", "historic", "amenity", "leisure", "natural", "shop", "sport", "man_made");

    @Value("${osm.import.file-path:classpath:turkey-260531.osm.pbf}")
    private Resource osmFile;

    private List<OsmPoi> pois;
    private int index;

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        index = executionContext.getInt("osm.reader.index", 0);
        try {
            pois = parsePbf();
            log.info("Parsed {} POIs from OSM file, resuming from index {}", pois.size(), index);
        } catch (IOException e) {
            throw new ItemStreamException("Failed to parse OSM PBF file", e);
        }
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        executionContext.putInt("osm.reader.index", index);
    }

    @Override
    public void close() throws ItemStreamException {
        pois = null;
    }

    @Override
    public OsmPoi read() {
        if (pois == null || index >= pois.size()) return null;
        return pois.get(index++);
    }

    private List<OsmPoi> parsePbf() throws IOException {
        // PBF guarantees order: nodes → ways → relations.
        // Collect all node coordinates first so way geometries can be resolved in the same pass.
        Map<Long, double[]> nodeCoords = new HashMap<>();
        List<OsmPoi> result = new ArrayList<>();

        try (InputStream in = new BufferedInputStream(osmFile.getInputStream())) {
            OsmIterator it = new PbfIterator(in, false);
            for (EntityContainer container : it) {
                switch (container.getType()) {
                    case Node -> handleNode((OsmNode) container.getEntity(), nodeCoords, result);
                    case Way  -> handleWay((OsmWay) container.getEntity(), nodeCoords, result);
                    case Relation -> handleRelation((OsmRelation) container.getEntity(), nodeCoords, result);
                }
            }
        }

        log.info("Node coord index: {} entries", nodeCoords.size());
        return result;
    }

    private void handleNode(OsmNode node, Map<Long, double[]> nodeCoords, List<OsmPoi> result) {
        nodeCoords.put(node.getId(), new double[]{node.getLatitude(), node.getLongitude()});
        Map<String, String> tags = extractTags(node);
        if (!isPoi(tags)) return;

        result.add(OsmPoi.builder()
            .osmId(node.getId())
            .osmType('N')
            .lat(node.getLatitude())
            .lon(node.getLongitude())
            .tags(tags)
            .build());
    }

    private void handleWay(OsmWay way, Map<Long, double[]> nodeCoords, List<OsmPoi> result) {
        Map<String, String> tags = extractTags(way);
        if (!isPoi(tags)) return;

        List<double[]> coords = resolveCoords(way, nodeCoords);
        if (coords.isEmpty()) return;

        double[] centroid = centroid(coords);
        String boundaryWkt = buildPolygonWkt(way, coords);

        result.add(OsmPoi.builder()
            .osmId(way.getId())
            .osmType('W')
            .lat(centroid[0])
            .lon(centroid[1])
            .boundaryWkt(boundaryWkt)
            .tags(tags)
            .build());
    }

    private void handleRelation(OsmRelation rel, Map<Long, double[]> nodeCoords, List<OsmPoi> result) {
        Map<String, String> tags = extractTags(rel);
        if (!isPoi(tags)) return;

        double[] centroid = relationCentroid(rel, nodeCoords);
        if (centroid == null) return;

        result.add(OsmPoi.builder()
            .osmId(rel.getId())
            .osmType('R')
            .lat(centroid[0])
            .lon(centroid[1])
            .tags(tags)
            .build());
    }

    private Map<String, String> extractTags(OsmEntity entity) {
        Map<String, String> tags = new HashMap<>(entity.getNumberOfTags() * 2);
        for (int i = 0; i < entity.getNumberOfTags(); i++) {
            OsmTag tag = entity.getTag(i);
            tags.put(tag.getKey(), tag.getValue());
        }
        return tags;
    }

    private boolean isPoi(Map<String, String> tags) {
        return tags.keySet().stream().anyMatch(POI_KEYS::contains);
    }

    private List<double[]> resolveCoords(OsmWay way, Map<Long, double[]> nodeCoords) {
        List<double[]> coords = new ArrayList<>(way.getNumberOfNodes());
        for (int i = 0; i < way.getNumberOfNodes(); i++) {
            double[] c = nodeCoords.get(way.getNodeId(i));
            if (c != null) coords.add(c);
        }
        return coords;
    }

    private double[] centroid(List<double[]> coords) {
        double sumLat = 0, sumLon = 0;
        for (double[] c : coords) {
            sumLat += c[0];
            sumLon += c[1];
        }
        return new double[]{sumLat / coords.size(), sumLon / coords.size()};
    }

    // Returns a WKT polygon for closed OSM ways. Rings are explicitly closed in WKT even when
    // the first/last node coords differ due to missing nodes in the PBF pass.
    private String buildPolygonWkt(OsmWay way, List<double[]> coords) {
        List<double[]> ring = dedupeConsecutive(coords);
        if (ring.size() < 3) return null;

        boolean osmClosed = way.getNodeId(0) == way.getNodeId(way.getNumberOfNodes() - 1);
        if (!ringClosed(ring)) {
            if (!osmClosed) return null;
            ring = new ArrayList<>(ring);
            double[] first = ring.get(0);
            ring.add(new double[]{first[0], first[1]});
        }

        if (ring.size() < 4) return null;

        StringBuilder sb = new StringBuilder("SRID=4326;POLYGON((");
        for (int i = 0; i < ring.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ring.get(i)[1]).append(' ').append(ring.get(i)[0]); // lon lat
        }
        sb.append("))");
        return sb.toString();
    }

    private List<double[]> dedupeConsecutive(List<double[]> coords) {
        List<double[]> out = new ArrayList<>(coords.size());
        for (double[] c : coords) {
            if (out.isEmpty() || !coordsEqual(c, out.get(out.size() - 1))) {
                out.add(c);
            }
        }
        return out;
    }

    private boolean ringClosed(List<double[]> ring) {
        return ring.size() >= 2 && coordsEqual(ring.get(0), ring.get(ring.size() - 1));
    }

    private boolean coordsEqual(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < 1e-7 && Math.abs(a[1] - b[1]) < 1e-7;
    }

    private double[] relationCentroid(OsmRelation rel, Map<Long, double[]> nodeCoords) {
        List<double[]> memberCoords = new ArrayList<>();
        for (int i = 0; i < rel.getNumberOfMembers(); i++) {
            OsmRelationMember member = rel.getMember(i);
            if (member.getType() == EntityType.Node) {
                double[] c = nodeCoords.get(member.getId());
                if (c != null) memberCoords.add(c);
            }
        }
        return memberCoords.isEmpty() ? null : centroid(memberCoords);
    }
}