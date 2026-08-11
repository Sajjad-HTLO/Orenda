package com.aitp.orenda.batch;

import com.aitp.orenda.model.PoiEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PoiJdbcItemWriter implements ItemWriter<PoiEntity> {

    private final JdbcTemplate jdbc;

    // ST_MakePoint takes (lon, lat). Boundary uses ST_GeographyFromText for EWKT (SRID=4326;POLYGON(...)).
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
            CASE WHEN ? IS NULL THEN NULL ELSE ST_GeographyFromText(?) END,
            ?, ARRAY['osm']::text[], ?::jsonb, false
        )
        ON CONFLICT (osm_id, osm_type) DO UPDATE SET
            wikidata_id        = EXCLUDED.wikidata_id,
            name_tr            = EXCLUDED.name_tr,
            name_en            = EXCLUDED.name_en,
            category           = EXCLUDED.category,
            subcategory        = EXCLUDED.subcategory,
            location           = EXCLUDED.location,
            boundary           = EXCLUDED.boundary,
            completeness_score = EXCLUDED.completeness_score,
            attributes         = EXCLUDED.attributes,
            last_synced_at     = NOW(),
            updated_at         = NOW()
        """;

    @Override
    public void write(Chunk<? extends PoiEntity> chunk) throws Exception {
        List<? extends PoiEntity> items = chunk.getItems();
        jdbc.batchUpdate(UPSERT, items, items.size(), (ps, poi) -> {
            ps.setLong(1,   poi.getOsmId());
            ps.setString(2, poi.getOsmType());
            ps.setString(3, poi.getWikidataId());      // nullable
            ps.setString(4, poi.getNameTr());
            ps.setString(5, poi.getNameEn());          // nullable
            ps.setString(6, poi.getCategory());
            ps.setString(7, poi.getSubcategory());     // nullable
            ps.setDouble(8, poi.getLon());             // lon first for ST_MakePoint
            ps.setDouble(9, poi.getLat());
            ps.setString(10, poi.getBoundaryWkt());    // null → CASE returns NULL
            ps.setString(11, poi.getBoundaryWkt());    // EWKT for ST_GeographyFromText
            ps.setShort(12,  poi.getCompletenessScore());
            ps.setString(13, poi.getAttributesJson());
        });
        log.info("Wrote {} POIs", items.size());
    }
}