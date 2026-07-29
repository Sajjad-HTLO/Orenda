package com.sajad.AITP.overpass;

import com.sajad.AITP.model.PoiEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * JDBC batch writer for Overpass-sourced POIs.
 * <p>
 * Uses PostGIS UPSERT on {@code (osm_id, osm_type)}. Overpass POIs use real OSM
 * IDs and types (N/W/R), so they may collide with existing POIs from the PBF import.
 * The UPSERT merges data: existing POIs get their attributes enriched with Overpass
 * data, and new POIs are inserted.
 * <p>
 * Sets {@code data_sources = ARRAY['overpass']} and marks as unverified.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OverpassJdbcItemWriter implements ItemWriter<PoiEntity> {

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
                ?, ARRAY['overpass']::text[], ?::jsonb, false
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

    @Override
    public void write(Chunk<? extends PoiEntity> chunk) throws Exception {
        List<? extends PoiEntity> items = chunk.getItems();
        jdbc.batchUpdate(UPSERT, items, items.size(), (ps, poi) -> {
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
        log.info("Wrote/updated {} Overpass POIs", items.size());
    }
}