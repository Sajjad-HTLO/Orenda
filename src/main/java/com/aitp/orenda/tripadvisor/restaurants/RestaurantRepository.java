package com.aitp.orenda.tripadvisor.restaurants;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Slf4j
@Repository
@ConditionalOnProperty(name = "tripadvisor.crawler.restaurants.enabled", havingValue = "true")
public class RestaurantRepository {

    private final JdbcTemplate jdbcTemplate;

    public RestaurantRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Upserts restaurant listings onto the shared {@code poi} model. Uses the
     * Tripadvisor id as the synthetic osm_id with osm_type 'T' and the standard
     * category/subcategory pair ({@code food_drink}/{@code restaurant}) used
     * across the app, so restaurants integrate with POI search and the lunch
     * planning flows.
     */
    public int upsertListings(List<RestaurantListing> listings) {
        log.info("Tripadvisor restaurant persistence starting. restaurantListings={}", listings.size());
        listings.stream().limit(5).forEach(listing ->
                log.info("Tripadvisor restaurant persistence sample. id={}, name={}, url={}",
                        listing.tripadvisorId(), listing.name(), listing.url()));
        int insertedOrUpdated = 0;
        for (RestaurantListing listing : listings) {
            int rows = jdbcTemplate.update("""
                    INSERT INTO poi (
                        osm_id, osm_type, wikidata_id, name_tr, name_en,
                        category, subcategory, location, boundary,
                        completeness_score, data_sources, attributes, verified,
                        last_synced_at, updated_at
                    ) VALUES (
                        ?, 'T', NULL, ?, ?,
                        'food_drink', 'restaurant', NULL, NULL,
                        ?, ARRAY['tripadvisor']::text[], ?::jsonb, false,
                        NOW(), NOW()
                    )
                    ON CONFLICT (osm_id, osm_type) DO UPDATE SET
                        name_tr = COALESCE(NULLIF(EXCLUDED.name_tr, ''), poi.name_tr),
                        name_en = COALESCE(EXCLUDED.name_en, poi.name_en),
                        category = EXCLUDED.category,
                        subcategory = EXCLUDED.subcategory,
                        completeness_score = GREATEST(poi.completeness_score, EXCLUDED.completeness_score),
                        data_sources = CASE
                            WHEN poi.data_sources @> ARRAY['tripadvisor']::text[] THEN poi.data_sources
                            ELSE array_append(poi.data_sources, 'tripadvisor')
                        END,
                        attributes = poi.attributes || EXCLUDED.attributes,
                        last_synced_at = NOW(),
                        updated_at = NOW()
                    """,
                    listing.tripadvisorId(),
                    nameOrFallback(listing),
                    listing.name(),
                    completenessScore(listing),
                    attributesJson(listing));
            insertedOrUpdated += rows;
        }
        log.info("Tripadvisor restaurant persistence finished. inputRestaurants={}, affectedRows={}, totalTripadvisorRestaurants={}",
                listings.size(), insertedOrUpdated, countRestaurants());
        return insertedOrUpdated;
    }

    public long countRestaurants() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM poi WHERE osm_type = 'T' AND category = 'food_drink'", Long.class);
        return count == null ? 0 : count;
    }

    private String nameOrFallback(RestaurantListing listing) {
        if (listing.name() != null && !listing.name().isBlank()) {
            return listing.name();
        }
        return "Tripadvisor restaurant " + listing.tripadvisorId();
    }

    private short completenessScore(RestaurantListing listing) {
        return (short) (listing.name() == null || listing.name().isBlank() ? 20 : 35);
    }

    private String attributesJson(RestaurantListing listing) {
        return """
                {
                  "tripadvisor_id": %d,
                  "tripadvisor_url": "%s",
                  "source_listing_url": "%s"
                }
                """.formatted(
                listing.tripadvisorId(),
                escapeJson(listing.url()),
                escapeJson(listing.sourceListingUrl()));
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Persists a single restaurant's detailed data (Stage 2) onto the shared
     * {@code poi} model. When coordinates are available they are stored in the
     * PostGIS {@code location} column; otherwise the row is kept without a
     * location so it can be geocoded later.
     */
    public int upsertRestaurantDetail(RestaurantDetail detail) {
        if (detail == null) {
            log.warn("Tripadvisor restaurant detail persistence skipped: detail is null");
            return 0;
        }
        log.info("Tripadvisor restaurant detail persistence starting. tripadvisorId={}, name='{}', lat={}, lon={}",
                detail.tripadvisorId(), detail.name(), detail.latitude(), detail.longitude());

        String name = nameOrFallback(detail);
        short completeness = completenessScore(detail);
        String attributes = attributesJson(detail);

        int rows;
        if (detail.latitude() != null && detail.longitude() != null) {
            rows = jdbcTemplate.update("""
                    INSERT INTO poi (
                        osm_id, osm_type, wikidata_id, name_tr, name_en,
                        category, subcategory, location, boundary,
                        completeness_score, data_sources, attributes, verified,
                        last_synced_at, updated_at
                    ) VALUES (
                        ?, 'T', NULL, ?, ?,
                        'food_drink', 'restaurant', ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, NULL,
                        ?, ARRAY['tripadvisor']::text[], ?::jsonb, false,
                        NOW(), NOW()
                    )
                    ON CONFLICT (osm_id, osm_type) DO UPDATE SET
                        name_tr = COALESCE(NULLIF(EXCLUDED.name_tr, ''), poi.name_tr),
                        name_en = COALESCE(EXCLUDED.name_en, poi.name_en),
                        category = EXCLUDED.category,
                        subcategory = EXCLUDED.subcategory,
                        location = COALESCE(EXCLUDED.location, poi.location),
                        completeness_score = GREATEST(poi.completeness_score, EXCLUDED.completeness_score),
                        data_sources = CASE
                            WHEN poi.data_sources @> ARRAY['tripadvisor']::text[] THEN poi.data_sources
                            ELSE array_append(poi.data_sources, 'tripadvisor')
                        END,
                        attributes = poi.attributes || EXCLUDED.attributes,
                        last_synced_at = NOW(),
                        updated_at = NOW()
                    """,
                    detail.tripadvisorId(),
                    name,
                    detail.name(),
                    detail.longitude(),
                    detail.latitude(),
                    completeness,
                    attributes);
        } else {
            rows = jdbcTemplate.update("""
                    INSERT INTO poi (
                        osm_id, osm_type, wikidata_id, name_tr, name_en,
                        category, subcategory, location, boundary,
                        completeness_score, data_sources, attributes, verified,
                        last_synced_at, updated_at
                    ) VALUES (
                        ?, 'T', NULL, ?, ?,
                        'food_drink', 'restaurant', NULL, NULL,
                        ?, ARRAY['tripadvisor']::text[], ?::jsonb, false,
                        NOW(), NOW()
                    )
                    ON CONFLICT (osm_id, osm_type) DO UPDATE SET
                        name_tr = COALESCE(NULLIF(EXCLUDED.name_tr, ''), poi.name_tr),
                        name_en = COALESCE(EXCLUDED.name_en, poi.name_en),
                        category = EXCLUDED.category,
                        subcategory = EXCLUDED.subcategory,
                        completeness_score = GREATEST(poi.completeness_score, EXCLUDED.completeness_score),
                        data_sources = CASE
                            WHEN poi.data_sources @> ARRAY['tripadvisor']::text[] THEN poi.data_sources
                            ELSE array_append(poi.data_sources, 'tripadvisor')
                        END,
                        attributes = poi.attributes || EXCLUDED.attributes,
                        last_synced_at = NOW(),
                        updated_at = NOW()
                    """,
                    detail.tripadvisorId(),
                    name,
                    detail.name(),
                    completeness,
                    attributes);
        }

        log.info("Tripadvisor restaurant detail persistence finished. tripadvisorId={}, affectedRows={}, totalTripadvisorRestaurants={}",
                detail.tripadvisorId(), rows, countRestaurants());
        return rows;
    }

    private String nameOrFallback(RestaurantDetail detail) {
        if (detail.name() != null && !detail.name().isBlank()) {
            return detail.name();
        }
        return "Tripadvisor restaurant " + detail.tripadvisorId();
    }

    private short completenessScore(RestaurantDetail detail) {
        int score = 20;
        if (detail.name() != null && !detail.name().isBlank()) {
            score += 15;
        }
        if (detail.latitude() != null && detail.longitude() != null) {
            score += 20;
        }
        if (detail.address() != null && !detail.address().isBlank()) {
            score += 10;
        }
        if (detail.rating() != null) {
            score += 10;
        }
        if (detail.reviewCount() != null) {
            score += 10;
        }
        if (detail.phone() != null && !detail.phone().isBlank()) {
            score += 5;
        }
        if (detail.description() != null && !detail.description().isBlank()) {
            score += 10;
        }
        return (short) Math.min(score, 100);
    }

    private String attributesJson(RestaurantDetail detail) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"tripadvisor_id\": ").append(detail.tripadvisorId()).append(",");
        json.append("\"tripadvisor_url\": \"").append(escapeJson(detail.url())).append("\",");
        json.append("\"source_listing_url\": \"").append(escapeJson(detail.sourceListingUrl())).append("\"");
        appendJsonField(json, "address", detail.address());
        appendJsonField(json, "locality", detail.locality());
        appendJsonField(json, "country", detail.country());
        appendJsonField(json, "postal_code", detail.postalCode());
        appendJsonField(json, "rating", detail.rating());
        appendJsonField(json, "review_count", detail.reviewCount());
        appendJsonField(json, "price_range", detail.priceRange());
        appendJsonField(json, "cuisine", detail.cuisine());
        appendJsonField(json, "phone", detail.phone());
        appendJsonField(json, "description", detail.description());
        appendJsonListField(json, "image_urls", detail.imageUrls());
        json.append("}");
        return json.toString();
    }

    private void appendJsonField(StringBuilder json, String key, Object value) {
        if (value == null) {
            return;
        }
        json.append(",");
        json.append("\"").append(key).append("\": ");
        if (value instanceof Number) {
            json.append(value);
        } else {
            json.append("\"").append(escapeJson(String.valueOf(value))).append("\"");
        }
    }

    private void appendJsonListField(StringBuilder json, String key, java.util.List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        json.append(",");
        json.append("\"").append(key).append("\": [");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(",");
            }
            json.append("\"").append(escapeJson(values.get(i))).append("\"");
        }
        json.append("]");
    }
}