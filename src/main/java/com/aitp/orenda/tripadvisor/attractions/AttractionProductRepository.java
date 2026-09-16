package com.aitp.orenda.tripadvisor.attractions;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Upserts Tripadvisor attraction products (dinner cruise tours) onto the
 * shared {@code poi} model. Uses the Tripadvisor product id as the synthetic
 * osm_id with osm_type 'T' and the {@code activity}/{@code dinner_cruise}
 * category pair, so the products integrate with POI search and trip planning.
 */
@Slf4j
@Repository
@ConditionalOnProperty(name = "tripadvisor.crawler.attraction-products.enabled", havingValue = "true")
public class AttractionProductRepository {

    private final JdbcTemplate jdbcTemplate;
    private final String subcategory;

    public AttractionProductRepository(JdbcTemplate jdbcTemplate,
            @org.springframework.beans.factory.annotation.Value("${tripadvisor.crawler.attraction-products.subcategory:dinner_cruise}") String subcategory) {
        this.jdbcTemplate = jdbcTemplate;
        this.subcategory = subcategory;
    }

    /**
     * Resolves the subcategory to persist: the category resolved from the
     * listing page heading when available, otherwise the configured
     * {@code tripadvisor.crawler.attraction-products.subcategory}.
     */
    private String resolveSubcategory(String categoryName) {
        return (categoryName != null && !categoryName.isBlank()) ? categoryName : subcategory;
    }

    /**
     * Resolves the {@code poi.category} for a crawled row from the listing it
     * came from: {@code attraction} for {@code /Attractions-...-Activities-...}
     * category listings (museums, malls, ...) and {@code activity} for bookable
     * {@code /Attraction_Products-...} tour listings.
     */
    private String resolveCategory(String sourceListingUrl) {
        return AttractionListingType.detect(sourceListingUrl).poiCategory();
    }

    public int upsertListings(List<AttractionProductListing> listings, String categoryName) {
        String sub = resolveSubcategory(categoryName);
        log.info("Tripadvisor attraction product persistence starting. productListings={}, subcategory='{}'", listings.size(), sub);
        int insertedOrUpdated = 0;
        for (AttractionProductListing listing : listings) {
            String category = resolveCategory(listing.sourceListingUrl());
            int rows = jdbcTemplate.update("""
                    INSERT INTO poi (
                        osm_id, osm_type, wikidata_id, name_tr, name_en,
                        category, subcategory, location, boundary,
                        completeness_score, data_sources, attributes, verified,
                        last_synced_at, updated_at
                    ) VALUES (
                        ?, 'T', NULL, ?, ?,
                        ?, ?, NULL, NULL,
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
                    category,
                    sub,
                    completenessScore(listing),
                    attributesJson(listing, sub));
            insertedOrUpdated += rows;
        }
        log.info("Tripadvisor attraction product persistence finished. inputProducts={}, affectedRows={}, totalAttractionProducts={}",
                listings.size(), insertedOrUpdated, countAttractionProducts());
        return insertedOrUpdated;
    }

    public long countAttractionProducts() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM poi WHERE osm_type = 'T' AND category IN ('activity', 'attraction', 'restaurant')", Long.class);
        return count == null ? 0 : count;
    }

    /**
     * Resolves the {@code poi.id} UUID for a Tripadvisor attraction product
     * (stored as osm_type 'T' with the Tripadvisor product id as osm_id). Used
     * to attach crawled reviews to the POI.
     */
    public java.util.UUID findPoiIdByTripadvisorId(long tripadvisorId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id FROM poi WHERE osm_type = 'T' AND osm_id = ?", java.util.UUID.class, tripadvisorId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * Returns true when the POI already has downloaded image binaries (rows in
     * {@code poi_image}). Used by the review crawl so re-crawling a product to
     * fetch its reviews does not re-download the image binaries.
     */
    public boolean hasImages(long tripadvisorId) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM poi_image WHERE osm_id = ? AND osm_type = 'T')",
                Boolean.class, tripadvisorId);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Loads attraction products that have not yet had traveler reviews crawled
     * (no rows in {@code poi_review_crawled}). Used by the "skip listing,
     * enrich existing" mode to fetch reviews without re-crawling the listing.
     */
    public List<AttractionProductListing> findAttractionProductsMissingReviews() {
        return jdbcTemplate.query("""
                SELECT p.osm_id,
                       COALESCE(p.name_en, p.name_tr) AS name,
                       p.attributes->>'tripadvisor_url' AS url,
                       p.attributes->>'source_listing_url' AS source_listing_url
                FROM poi p
                WHERE p.osm_type = 'T'
                  AND p.category IN ('activity', 'attraction', 'restaurant')
                  AND NOT EXISTS (SELECT 1 FROM poi_review_crawled r WHERE r.poi_id = p.id)
                ORDER BY p.updated_at
                """, (rs, rowNum) -> AttractionProductListing.builder()
                .tripadvisorId(rs.getLong("osm_id"))
                .name(rs.getString("name"))
                .url(rs.getString("url"))
                .sourceListingUrl(rs.getString("source_listing_url"))
                .build());
    }

    /**
     * Loads the attraction products that were persisted by a previous listing
     * crawl but do not yet have images downloaded (no {@code image_urls} key in
     * the {@code attributes} JSONB). Used by the "skip listing, enrich existing"
     * mode so the crawler can fetch images for the remaining products without
     * re-crawling the listing page (which would re-trigger DataDome).
     */
    public List<AttractionProductListing> findAttractionProductsMissingImages() {
        return jdbcTemplate.query("""
                SELECT osm_id,
                       COALESCE(name_en, name_tr) AS name,
                       attributes->>'tripadvisor_url' AS url,
                       attributes->>'source_listing_url' AS source_listing_url
                FROM poi
                WHERE osm_type = 'T'
                  AND category IN ('activity', 'attraction', 'restaurant')
                  AND NOT (attributes ? 'image_urls')
                ORDER BY updated_at
                """, (rs, rowNum) -> AttractionProductListing.builder()
                .tripadvisorId(rs.getLong("osm_id"))
                .name(rs.getString("name"))
                .url(rs.getString("url"))
                .sourceListingUrl(rs.getString("source_listing_url"))
                .build());
    }

    private String nameOrFallback(AttractionProductListing listing) {
        if (listing.name() != null && !listing.name().isBlank()) {
            return listing.name();
        }
        return "Tripadvisor attraction product " + listing.tripadvisorId();
    }

    private short completenessScore(AttractionProductListing listing) {
        return (short) (listing.name() == null || listing.name().isBlank() ? 20 : 35);
    }

    private String attributesJson(AttractionProductListing listing, String sub) {
        return """
                {
                  "tripadvisor_id": %d,
                  "tripadvisor_url": "%s",
                  "source_listing_url": "%s",
                  "category_type": "%s"
                }
                """.formatted(
                listing.tripadvisorId(),
                escapeJson(listing.url()),
                escapeJson(listing.sourceListingUrl()),
                escapeJson(sub));
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Persists a single attraction product's detailed data (Stage 2) onto the
     * shared {@code poi} model. Tour products rarely expose coordinates on
     * Tripadvisor; when available they are stored in the PostGIS
     * {@code location} column, otherwise the row is kept without a location.
     */
    public int upsertProductDetail(AttractionProductDetail detail, String categoryName) {
        if (detail == null) {
            log.warn("Tripadvisor attraction product detail persistence skipped: detail is null");
            return 0;
        }
        String sub = resolveSubcategory(categoryName);
        log.info("Tripadvisor attraction product detail persistence starting. tripadvisorId={}, name='{}', lat={}, lon={}, subcategory='{}'",
                detail.tripadvisorId(), detail.name(), detail.latitude(), detail.longitude(), sub);

        String name = nameOrFallback(detail);
        short completeness = completenessScore(detail);
        String attributes = attributesJson(detail, sub);
        String category = resolveCategory(detail.sourceListingUrl());

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
                        ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, NULL,
                        ?, ARRAY['tripadvisor']::text[], ?::jsonb, false,
                        NOW(), NOW()
                    )
                    ON CONFLICT (osm_id, osm_type) DO UPDATE SET
                        name_tr = COALESCE(NULLIF(EXCLUDED.name_tr, ''), poi.name_tr),
                        name_en = COALESCE(EXCLUDED.name_en, poi.name_en),
                        category = EXCLUDED.category,
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
                    category,
                    sub,
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
                        ?, ?, NULL, NULL,
                        ?, ARRAY['tripadvisor']::text[], ?::jsonb, false,
                        NOW(), NOW()
                    )
                    ON CONFLICT (osm_id, osm_type) DO UPDATE SET
                        name_tr = COALESCE(NULLIF(EXCLUDED.name_tr, ''), poi.name_tr),
                        name_en = COALESCE(EXCLUDED.name_en, poi.name_en),
                        category = EXCLUDED.category,
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
                    category,
                    sub,
                    completeness,
                    attributes);
        }

        log.info("Tripadvisor attraction product detail persistence finished. tripadvisorId={}, affectedRows={}, totalAttractionProducts={}",
                detail.tripadvisorId(), rows, countAttractionProducts());
        return rows;
    }

    private String nameOrFallback(AttractionProductDetail detail) {
        if (detail.name() != null && !detail.name().isBlank()) {
            return detail.name();
        }
        return "Tripadvisor attraction product " + detail.tripadvisorId();
    }

    private short completenessScore(AttractionProductDetail detail) {
        int score = 20;
        if (detail.name() != null && !detail.name().isBlank()) {
            score += 15;
        }
        if (detail.latitude() != null && detail.longitude() != null) {
            score += 10;
        }
        if (detail.rating() != null) {
            score += 10;
        }
        if (detail.reviewCount() != null) {
            score += 10;
        }
        if (detail.price() != null && !detail.price().isBlank()) {
            score += 10;
        }
        if (detail.duration() != null && !detail.duration().isBlank()) {
            score += 10;
        }
        if (detail.description() != null && !detail.description().isBlank()) {
            score += 10;
        }
        if (detail.imageUrls() != null && !detail.imageUrls().isEmpty()) {
            score += 5;
        }
        return (short) Math.min(score, 100);
    }

    private String attributesJson(AttractionProductDetail detail, String sub) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"tripadvisor_id\": ").append(detail.tripadvisorId()).append(",");
        json.append("\"tripadvisor_url\": \"").append(escapeJson(detail.url())).append("\",");
        json.append("\"source_listing_url\": \"").append(escapeJson(detail.sourceListingUrl())).append("\",");
        json.append("\"category_type\": \"").append(escapeJson(sub)).append("\"");
        appendJsonField(json, "rating", detail.rating());
        appendJsonField(json, "review_count", detail.reviewCount());
        appendJsonField(json, "price", detail.price());
        appendJsonField(json, "cost", detail.cost());
        appendJsonField(json, "duration", detail.duration());
        appendJsonField(json, "cancellation_policy", detail.cancellationPolicy());
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

    private void appendJsonListField(StringBuilder json, String key, List<String> values) {
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
