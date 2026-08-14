package com.aitp.orenda.enrichment.wikipedia;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aitp.orenda.enrichment.PoiEnrichmentCandidate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@StepScope
@RequiredArgsConstructor
@Slf4j
public class WikipediaEnrichmentItemReader implements ItemReader<PoiEnrichmentCandidate> {

    private static final String SELECT_SQL = """
            SELECT p.id, p.name_tr, p.name_en, p.wikidata_id,
                   p.category, p.completeness_score, p.attributes::text
            FROM poi p
            WHERE NOT EXISTS (
                SELECT 1 FROM poi_source_data psd
                WHERE psd.poi_id = p.id AND psd.source = 'wikipedia'
            )
              AND (p.name_tr <> '' OR p.name_en IS NOT NULL
                   OR p.wikidata_id IS NOT NULL
                   OR jsonb_exists(p.attributes, 'wikipedia'))
              AND mod(abs(hashtext(p.id::text)), ?) = ?
            ORDER BY
                CASE WHEN jsonb_exists(p.attributes, 'wikipedia') THEN 0 ELSE 1 END,
                p.completeness_score ASC,
                p.id
            LIMIT ?
            """;

    private static final String COUNT_TOTAL = "SELECT count(*) FROM poi";
    private static final String COUNT_WITH_NAME = "SELECT count(*) FROM poi WHERE name_tr <> '' OR name_en IS NOT NULL";
    private static final String COUNT_NOT_ENRICHED = """
            SELECT count(*) FROM poi p
            WHERE NOT EXISTS (SELECT 1 FROM poi_source_data psd WHERE psd.poi_id = p.id AND psd.source = 'wikipedia')
            """;
    private static final String COUNT_ELIGIBLE = """
            SELECT count(*) FROM poi p
            WHERE NOT EXISTS (SELECT 1 FROM poi_source_data psd WHERE psd.poi_id = p.id AND psd.source = 'wikipedia')
              AND (p.name_tr <> '' OR p.name_en IS NOT NULL
                   OR p.wikidata_id IS NOT NULL
                   OR jsonb_exists(p.attributes, 'wikipedia'))
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${wikipedia.enrichment.max-items-per-run:50}")
    private int maxItemsPerRun;

    @Value("#{stepExecutionContext['partition']}")
    private Integer partition;

    @Value("#{stepExecutionContext['partitionCount']}")
    private Integer partitionCount;

    private Iterator<PoiEnrichmentCandidate> iterator;

    @Override
    public PoiEnrichmentCandidate read() {
        if (iterator == null) {
            iterator = loadCandidates().iterator();
        }
        if (!iterator.hasNext()) {
            return null;
        }
        PoiEnrichmentCandidate candidate = iterator.next();
        log.info("Enriching POI id={} name_tr=\"{}\" name_en=\"{}\" wikidata_id={} category={} completeness={}",
                candidate.getId(), candidate.getNameTr(), candidate.getNameEn(),
                candidate.getWikidataId(), candidate.getCategory(), candidate.getCompletenessScore());
        return candidate;
    }

    private List<PoiEnrichmentCandidate> loadCandidates() {
        int shard = partition != null ? partition : 0;
        int shards = partitionCount != null ? partitionCount : 1;

        // Diagnostic: count each filter step so we know which one eliminates rows
        if (shard == 0) {
            int total = jdbc.queryForObject(COUNT_TOTAL, Integer.class);
            int withName = jdbc.queryForObject(COUNT_WITH_NAME, Integer.class);
            int notEnriched = jdbc.queryForObject(COUNT_NOT_ENRICHED, Integer.class);
            int eligible = jdbc.queryForObject(COUNT_ELIGIBLE, Integer.class);
            log.info("DB diagnostic | total POIs: {} | with name: {} | not yet enriched: {} | eligible (combined): {}",
                    total, withName, notEnriched, eligible);
        }

        List<PoiEnrichmentCandidate> candidates = jdbc.query(SELECT_SQL, (rs, rowNum) -> {
            Map<String, Object> attributes = parseAttributes(rs.getString("attributes"));
            return PoiEnrichmentCandidate.builder()
                    .id(UUID.fromString(rs.getString("id")))
                    .nameTr(rs.getString("name_tr"))
                    .nameEn(rs.getString("name_en"))
                    .wikidataId(rs.getString("wikidata_id"))
                    .category(rs.getString("category"))
                    .completenessScore(rs.getShort("completeness_score"))
                    .attributes(attributes)
                    .build();
        }, shards, shard, maxItemsPerRun);
        log.info("Partition {}/{} loaded {} POI candidates", shard, shards, candidates.size());
        return candidates;
    }

    private Map<String, Object> parseAttributes(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
