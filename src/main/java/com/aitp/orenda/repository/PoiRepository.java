package com.aitp.orenda.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aitp.orenda.model.FeedbackRequest;
import com.aitp.orenda.model.PoiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class PoiRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper jackson = new ObjectMapper();

    // JSONB filter: exclude POIs with status='closed' or status='duplicate'
    private static final String STATUS_FILTER = """
            (p.attributes->>'status' IS NULL OR p.attributes->>'status' NOT IN ('closed','duplicate'))
            """;

    // ── Nearby ──────────────────────────────────────────────────────────────

    private static final String NEARBY_SQL = """
        WITH ref AS (SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography AS pt)
        SELECT p.id, p.osm_id, p.osm_type, p.name_tr, p.name_en,
               p.category, p.subcategory,
               ST_Y(p.location::geometry) AS lat,
               ST_X(p.location::geometry) AS lon,
               p.completeness_score,
               p.attributes::text        AS attributes_json,
               ST_Distance(p.location, ref.pt) / 1000.0 AS distance_km
        FROM poi p, ref
        WHERE ST_DWithin(p.location, ref.pt, ?)
              AND """ + STATUS_FILTER + """
            ORDER BY distance_km
            LIMIT ? OFFSET ?
            """;

    private static final String NEARBY_CATEGORY_SQL = """
            WITH ref AS (SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography AS pt)
            SELECT p.id, p.osm_id, p.osm_type, p.name_tr, p.name_en,
                   p.category, p.subcategory,
                   ST_Y(p.location::geometry) AS lat,
                   ST_X(p.location::geometry) AS lon,
                   p.completeness_score,
                   p.attributes::text        AS attributes_json,
                   ST_Distance(p.location, ref.pt) / 1000.0 AS distance_km
            FROM poi p, ref
            WHERE ST_DWithin(p.location, ref.pt, ?)
              AND p.category = ?
              AND """ + STATUS_FILTER + """
        ORDER BY distance_km
        LIMIT ? OFFSET ?
        """;

    public List<PoiResponse> findNearby(double lat, double lon, double radiusKm,
                                        String category, int page, int size) {
        double radiusM = radiusKm * 1000;
        long offset = (long) page * size;

        if (category == null || category.isBlank()) {
            return jdbc.query(NEARBY_SQL, poiRowMapper(),
                    lon, lat, radiusM, size, offset);
        }
        return jdbc.query(NEARBY_CATEGORY_SQL, poiRowMapper(),
                lon, lat, radiusM, category, size, offset);
    }

    // ── By ID ───────────────────────────────────────────────────────────────

    private static final String BY_ID_SQL = """
        SELECT id, osm_id, osm_type, name_tr, name_en,
               category, subcategory,
               ST_Y(location::geometry) AS lat,
               ST_X(location::geometry) AS lon,
               completeness_score,
               attributes::text AS attributes_json,
               NULL::double precision   AS distance_km
        FROM poi
        WHERE id = ?::uuid
        """;

    public Optional<PoiResponse> findById(String id) {
        List<PoiResponse> rows = jdbc.query(BY_ID_SQL, poiRowMapper(), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    // ── Text search ─────────────────────────────────────────────────────────

    private static final String SEARCH_SQL = """
            SELECT p.id, p.osm_id, p.osm_type, p.name_tr, p.name_en,
                   p.category, p.subcategory,
                   ST_Y(p.location::geometry) AS lat,
                   ST_X(p.location::geometry) AS lon,
                   p.completeness_score,
                   p.attributes::text AS attributes_json,
               NULL::double precision   AS distance_km
            FROM poi p
            WHERE (p.name_tr ILIKE ? OR p.name_en ILIKE ?)
              AND """ + STATUS_FILTER + """
            ORDER BY p.completeness_score DESC
        LIMIT ? OFFSET ?
        """;

    public List<PoiResponse> search(String q, int page, int size) {
        String pattern = "%" + q + "%";
        return jdbc.query(SEARCH_SQL, poiRowMapper(),
            pattern, pattern, size, (long) page * size);
    }

    // ── Categories ──────────────────────────────────────────────────────────

    private static final String CATEGORIES_SQL = """
        SELECT category, subcategory, COUNT(*) AS count
        FROM poi
        GROUP BY category, subcategory
        ORDER BY category, count DESC
        """;

    public List<Map<String, Object>> findCategories() {
        return jdbc.queryForList(CATEGORIES_SQL);
    }

    // ── Feedback ────────────────────────────────────────────────────────────

    /**
     * Persists a feedback row and immediately applies its effect to the POI
     * (status in attributes JSONB, verified flag, completeness score, optional
     * location correction). Both statements run in one transaction.
     */
    @Transactional
    public void saveFeedback(FeedbackRequest req) {
        jdbc.update("""
                        INSERT INTO poi_feedback (poi_id, feedback_type, details, session_id)
                        VALUES (?::uuid, ?, ?, ?)
                        """,
                req.getPoiId(), req.getType().name(), req.getDetails(), req.getSessionId());

        applyPoiStatus(req);
    }

    private void applyPoiStatus(FeedbackRequest req) {
        switch (req.getType()) {
            case CLOSED -> jdbc.update("""
                    UPDATE poi
                    SET attributes = attributes || '{"status":"closed"}'::jsonb,
                        completeness_score = GREATEST(0, completeness_score - 20),
                        updated_at = NOW()
                    WHERE id = ?::uuid
                    """, req.getPoiId());
            case DUPLICATE -> jdbc.update("""
                    UPDATE poi
                    SET attributes = attributes || '{"status":"duplicate"}'::jsonb,
                        completeness_score = GREATEST(0, completeness_score - 15),
                        updated_at = NOW()
                    WHERE id = ?::uuid
                    """, req.getPoiId());
            case MOVED -> {
                if (req.getNewLat() != null && req.getNewLon() != null) {
                    jdbc.update("""
                            UPDATE poi
                            SET verified = false,
                                attributes = attributes || '{"needs_review":true}'::jsonb,
                                completeness_score = GREATEST(0, completeness_score - 10),
                                location = ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                                updated_at = NOW()
                            WHERE id = ?::uuid
                            """, req.getNewLon(), req.getNewLat(), req.getPoiId());
                } else {
                    jdbc.update("""
                            UPDATE poi
                            SET verified = false,
                                attributes = attributes || '{"needs_review":true}'::jsonb,
                                completeness_score = GREATEST(0, completeness_score - 10),
                                updated_at = NOW()
                            WHERE id = ?::uuid
                            """, req.getPoiId());
                }
            }
            case INACCURATE -> jdbc.update("""
                    UPDATE poi
                    SET verified = false,
                        attributes = attributes || '{"needs_review":true}'::jsonb,
                        completeness_score = GREATEST(0, completeness_score - 10),
                        updated_at = NOW()
                    WHERE id = ?::uuid
                    """, req.getPoiId());
            case OTHER -> jdbc.update("""
                    UPDATE poi
                    SET verified = false,
                        attributes = attributes || '{"needs_review":true}'::jsonb,
                        completeness_score = GREATEST(0, completeness_score - 5),
                        updated_at = NOW()
                    WHERE id = ?::uuid
                    """, req.getPoiId());
        }
    }

    // ── Alternative suggestion ──────────────────────────────────────────────

    private static final String ALTERNATIVE_SQL = """
            WITH ref AS (SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography AS pt)
            SELECT p.id, p.osm_id, p.osm_type, p.name_tr, p.name_en,
                   p.category, p.subcategory,
                   ST_Y(p.location::geometry) AS lat,
                   ST_X(p.location::geometry) AS lon,
                   p.completeness_score,
                   p.attributes::text        AS attributes_json,
                   ST_Distance(p.location, ref.pt) / 1000.0 AS distance_km
            FROM poi p, ref
            WHERE ST_DWithin(p.location, ref.pt, ?)
              AND p.id <> ?::uuid
              AND """ + STATUS_FILTER + """
            ORDER BY distance_km
            LIMIT 1
            """;

    private static final String ALTERNATIVE_CATEGORY_SQL = """
            WITH ref AS (SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography AS pt)
            SELECT p.id, p.osm_id, p.osm_type, p.name_tr, p.name_en,
                   p.category, p.subcategory,
                   ST_Y(p.location::geometry) AS lat,
                   ST_X(p.location::geometry) AS lon,
                   p.completeness_score,
                   p.attributes::text        AS attributes_json,
                   ST_Distance(p.location, ref.pt) / 1000.0 AS distance_km
            FROM poi p, ref
            WHERE ST_DWithin(p.location, ref.pt, ?)
              AND p.id <> ?::uuid
              AND p.category = ?
              AND """ + STATUS_FILTER + """
            ORDER BY distance_km
            LIMIT 1
            """;

    /**
     * Nearest non-excluded POI near the reported POI, preferring the same category.
     * Falls back to any category when no same-category alternative is within range.
     */
    public Optional<PoiResponse> findAlternative(String poiId, String category,
                                                 double lat, double lon, double radiusKm) {
        double radiusM = radiusKm * 1000;

        List<PoiResponse> sameCategory = jdbc.query(ALTERNATIVE_CATEGORY_SQL, poiRowMapper(),
                lon, lat, radiusM, poiId, category);
        if (!sameCategory.isEmpty()) {
            return Optional.of(sameCategory.get(0));
        }

        List<PoiResponse> any = jdbc.query(ALTERNATIVE_SQL, poiRowMapper(),
                lon, lat, radiusM, poiId);
        return any.isEmpty() ? Optional.empty() : Optional.of(any.get(0));
    }

    // ── RowMapper ───────────────────────────────────────────────────────────

    private RowMapper<PoiResponse> poiRowMapper() {
        return (rs, rowNum) -> {
            Map<String, Object> attributes = parseAttributes(rs.getString("attributes_json"));

            double rawDist = rs.getDouble("distance_km");
            Double distanceKm = rs.wasNull() ? null : rawDist;

            return PoiResponse.builder()
                .id(rs.getString("id"))
                .osmId(rs.getLong("osm_id"))
                .osmType(rs.getString("osm_type"))
                .nameTr(rs.getString("name_tr"))
                .nameEn(rs.getString("name_en"))
                .category(rs.getString("category"))
                .subcategory(rs.getString("subcategory"))
                .lat(rs.getDouble("lat"))
                .lon(rs.getDouble("lon"))
                .completenessScore(rs.getInt("completeness_score"))
                .distanceKm(distanceKm)
                .attributes(attributes)
                .build();
        };
    }

    private Map<String, Object> parseAttributes(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return jackson.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse attributes JSON: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }
}
