package com.aitp.orenda.enrichment.wikipedia;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aitp.orenda.enrichment.PoiEnrichmentResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class WikipediaEnrichmentItemWriter implements ItemWriter<PoiEnrichmentResult> {

    private static final String UPDATE_POI = """
            UPDATE poi SET
                attributes = attributes || ?::jsonb,
                data_sources = CASE
                    WHEN 'wikipedia' = ANY(data_sources) THEN data_sources
                    ELSE array_append(data_sources, 'wikipedia')
                END,
                completeness_score = GREATEST(completeness_score, ?),
                updated_at = NOW()
            WHERE id = ?::uuid
            """;

    private static final String INSERT_SOURCE = """
            INSERT INTO poi_source_data (poi_id, source, field, value)
            VALUES (?::uuid, 'wikipedia', ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final WikipediaEnrichmentStats stats;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void write(Chunk<? extends PoiEnrichmentResult> chunk) throws Exception {
        List<? extends PoiEnrichmentResult> items = chunk.getItems();

        // Batch the POI updates
        List<Object[]> updateBatch = new ArrayList<>();
        // Batch the source field inserts
        List<Object[]> sourceBatch = new ArrayList<>();

        for (PoiEnrichmentResult result : items) {
            if (result.isFound() && !result.getAttributeUpdates().isEmpty()) {
                String json = objectMapper.writeValueAsString(result.getAttributeUpdates());
                updateBatch.add(new Object[]{json, result.getCompletenessScore(), result.getPoiId().toString()});
            }

            for (PoiEnrichmentResult.SourceField field : result.getSourceFields()) {
                sourceBatch.add(new Object[]{result.getPoiId().toString(), field.getField(), field.getValue()});
            }

            stats.record(result);
        }

        if (!updateBatch.isEmpty()) {
            jdbc.batchUpdate(UPDATE_POI, updateBatch);
        }

        if (!sourceBatch.isEmpty()) {
            jdbc.batchUpdate(INSERT_SOURCE, sourceBatch);
        }
    }
}
