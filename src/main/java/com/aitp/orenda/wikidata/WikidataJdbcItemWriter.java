package com.aitp.orenda.wikidata;

import com.aitp.orenda.model.PoiEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * JDBC batch writer for Wikidata-sourced POIs.
 * <p>
 * Uses PostGIS UPSERT on {@code (osm_id, osm_type)}. Wikidata POIs use negative
 * Q-ID numbers as osm_id and 'Q' as osm_type, so they never collide with real
 * OSM POIs (which use positive IDs and N/W/R types).
 * <p>
 * Sets {@code data_sources = ARRAY['wikidata']} and marks as unverified.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WikidataJdbcItemWriter implements ItemWriter<PoiEntity>, ItemStream {

    private static final String UPSERT = """
            INSERT INTO poi (
                osm_id, osm_type, wikidata_id, name_tr, name_en,
                category, subcategory,
                location,
                boundary,
                completeness_score, data_sources, attributes, verified
            ) VALUES (
                ?, ?, ?, ?, ?,
                ?, ?,
                ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                NULL,
                ?, ARRAY['wikidata']::text[], ?::jsonb, false
            )
            ON CONFLICT (osm_id, osm_type) DO UPDATE SET
                wikidata_id        = COALESCE(EXCLUDED.wikidata_id, poi.wikidata_id),
                name_tr            = EXCLUDED.name_tr,
                name_en            = COALESCE(EXCLUDED.name_en, poi.name_en),
                category           = EXCLUDED.category,
                subcategory        = EXCLUDED.subcategory,
                location           = EXCLUDED.location,
                completeness_score = GREATEST(EXCLUDED.completeness_score, poi.completeness_score),
                attributes         = poi.attributes || EXCLUDED.attributes,
                last_synced_at     = NOW(),
                updated_at         = NOW()
            """;
    private final JdbcTemplate jdbc;
    private final AtomicLong chunkCount = new AtomicLong(0);
    private final AtomicLong writtenTotal = new AtomicLong(0);
    private final AtomicLong affectedTotal = new AtomicLong(0);

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        chunkCount.set(0);
        writtenTotal.set(0);
        affectedTotal.set(0);
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        // No-op: counters are for logging only.
    }

    @Override
    public void close() throws ItemStreamException {
        log.info("Wikidata write summary | stage=write | chunks={} | total_written={} | total_affected={}",
                chunkCount.get(), writtenTotal.get(), affectedTotal.get());
    }


    @Override
    public void write(Chunk<? extends PoiEntity> chunk) throws Exception {
        List<? extends PoiEntity> items = chunk.getItems();
        long chunkNo = chunkCount.incrementAndGet();
        log.info("Wikidata write | stage=write | event=before_batch_update | chunk={} | chunk_size={} | first_items={}",
                chunkNo, items.size(), sampleItems(items, 3));

        try {
            int[][] counts = jdbc.batchUpdate(UPSERT, items, items.size(), (ps, poi) -> {
                ps.setLong(1, poi.getOsmId());
                ps.setString(2, poi.getOsmType());
                ps.setString(3, poi.getWikidataId());
                ps.setString(4, poi.getNameTr());
                ps.setString(5, poi.getNameEn());
                ps.setString(6, poi.getCategory());
                ps.setString(7, poi.getSubcategory());
                ps.setDouble(8, poi.getLon());
                ps.setDouble(9, poi.getLat());
                ps.setShort(10, poi.getCompletenessScore());
                ps.setString(11, poi.getAttributesJson());
            });

            long chunkAffected = countAffectedRows(counts, items.size());
            long totalAffected = affectedTotal.addAndGet(chunkAffected);
            long totalWritten = writtenTotal.addAndGet(items.size());

            log.info("Wikidata write | stage=write | event=after_batch_update | chunk={} | chunk_size={} | chunk_affected={} | total_written={} | total_affected={}",
                    chunkNo, items.size(), chunkAffected, totalWritten, totalAffected);
        } catch (Exception e) {
            log.error("Wikidata write FAILED | stage=write | chunk={} | chunk_size={} | first_items={}",
                    chunkNo, items.size(), sampleItems(items, 5), e);
            throw e;
        }
    }

    private long countAffectedRows(int[][] batchCounts, int fallbackSize) {
        long total = 0;
        for (int[] statementCounts : batchCounts) {
            for (int count : statementCounts) {
                if (count >= 0) {
                    total += count;
                } else if (count == Statement.SUCCESS_NO_INFO) {
                    total += 1;
                }
            }
        }
        return total > 0 ? total : fallbackSize;
    }

    private String sampleItems(List<? extends PoiEntity> items, int limit) {
        return items.stream()
                .limit(limit)
                .map(p -> String.format("%s(osm_id=%d,lat=%.6f,lon=%.6f)",
                        p.getWikidataId(), p.getOsmId(), p.getLat(), p.getLon()))
                .collect(Collectors.joining("; "));
    }
}