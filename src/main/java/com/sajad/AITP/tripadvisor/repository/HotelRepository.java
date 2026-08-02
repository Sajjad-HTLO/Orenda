package com.sajad.AITP.tripadvisor.repository;

import com.sajad.AITP.tripadvisor.model.HotelListing;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Slf4j
@Repository
@ConditionalOnProperty(name = "tripadvisor.crawler.enabled", havingValue = "true")
public class HotelRepository {

    private final JdbcTemplate jdbcTemplate;

    public HotelRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int upsertListings(List<HotelListing> listings) {
        log.info("Tripadvisor poi persistence starting. hotelListings={}", listings.size());
        listings.stream().limit(5).forEach(listing ->
                log.info("Tripadvisor poi persistence sample. id={}, name={}, url={}",
                        listing.tripadvisorId(), listing.name(), listing.url()));
        int insertedOrUpdated = 0;
        for (HotelListing listing : listings) {
            int rows = jdbcTemplate.update("""
                    INSERT INTO poi (
                        osm_id, osm_type, wikidata_id, name_tr, name_en,
                        category, subcategory, location, boundary,
                        completeness_score, data_sources, attributes, verified,
                        last_synced_at, updated_at
                    ) VALUES (
                        ?, 'T', NULL, ?, ?,
                        'accommodation', 'hotel', NULL, NULL,
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
        log.info("Tripadvisor poi persistence finished. inputListings={}, affectedRows={}, totalTripadvisorPois={}",
                listings.size(), insertedOrUpdated, countHotels());
        return insertedOrUpdated;
    }

    public long countHotels() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM poi WHERE osm_type = 'T'", Long.class);
        return count == null ? 0 : count;
    }

    private String nameOrFallback(HotelListing listing) {
        if (listing.name() != null && !listing.name().isBlank()) {
            return listing.name();
        }
        return "Tripadvisor hotel " + listing.tripadvisorId();
    }

    private short completenessScore(HotelListing listing) {
        return (short) (listing.name() == null || listing.name().isBlank() ? 20 : 35);
    }

    private String attributesJson(HotelListing listing) {
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
}
