package com.sajad.AITP.overpass;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sajad.AITP.model.PoiEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts an {@link OverpassRawPoi} into a {@link PoiEntity} ready for PostgreSQL insertion.
 * <p>
 * Naming convention for Overpass-sourced POIs:
 * <ul>
 *   <li>{@code osm_id} = positive OSM element ID (as assigned by OSM)</li>
 *   <li>{@code osm_type} = 'N', 'W', or 'R' (same as OSM convention)</li>
 *   <li>{@code data_sources} = ARRAY['overpass']</li>
 * </ul>
 * <p>
 * Since Overpass returns real OSM elements, these may collide with existing POIs
 * from the PBF import. The writer handles this via UPSERT (ON CONFLICT DO UPDATE).
 * <p>
 * Completeness scoring: Overpass items with coordinates, names, and tags receive
 * a base score of 30-60 depending on the data available.
 */
@Slf4j
@Component
public class OverpassProcessor implements ItemProcessor<OverpassRawPoi, PoiEntity> {

    private final ObjectMapper jackson = new ObjectMapper();

    @Override
    public PoiEntity process(OverpassRawPoi poi) throws Exception {
        short score = calculateCompleteness(poi);
        String attributesJson = jackson.writeValueAsString(buildAttributes(poi));

        return PoiEntity.builder()
                .osmId(poi.getOsmId())
                .osmType(poi.getElementType())
                .wikidataId(poi.getWikidataId())
                .nameTr(poi.getNameTr() != null ? poi.getNameTr() : "")
                .nameEn(poi.getNameEn())
                .category(poi.getCategory())
                .subcategory(poi.getSubcategory())
                .lat(poi.getLat())
                .lon(poi.getLon())
                .boundaryWkt(null)  // Overpass items are point features (centroids for ways/relations)
                .completenessScore(score)
                .attributesJson(attributesJson)
                .build();
    }

    private Map<String, Object> buildAttributes(OverpassRawPoi poi) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("source", "overpass");
        attrs.put("osm_tag", poi.getOsmTagKey() + "=" + poi.getOsmTagValue());
        attrs.put("overpass_query", poi.getQueryLabel());
        if (poi.getNameTr() != null) attrs.put("name", poi.getNameTr());
        if (poi.getNameEn() != null) attrs.put("name:en", poi.getNameEn());
        if (poi.getWikidataId() != null) attrs.put("wikidata", poi.getWikidataId());
        if (poi.getWikipediaTag() != null) attrs.put("wikipedia", poi.getWikipediaTag());
        return attrs;
    }

    /**
     * Completeness score for Overpass items:
     * Base 20 (location) + 20 (name) + 10 (English name) + 10 (wikidata link)
     */
    private short calculateCompleteness(OverpassRawPoi poi) {
        int score = 20; // always has coordinates
        if (poi.getNameTr() != null && !poi.getNameTr().isBlank()) score += 20;
        if (poi.getNameEn() != null && !poi.getNameEn().isBlank()) score += 10;
        if (poi.getWikidataId() != null && !poi.getWikidataId().isBlank()) score += 10;
        if (poi.getWikipediaTag() != null && !poi.getWikipediaTag().isBlank()) score += 10;
        return (short) Math.min(score, 100);
    }
}