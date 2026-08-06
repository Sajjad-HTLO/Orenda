package com.sajad.AITP.wikidata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sajad.AITP.model.PoiEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Converts a {@link WikidataRawPoi} into a {@link PoiEntity} ready for PostgreSQL insertion.
 * <p>
 * Naming convention for Wikidata-sourced POIs:
 * <ul>
 *   <li>{@code osm_id} = negative Q-ID numeric part (e.g., Q12345 → -12345)</li>
 *   <li>{@code osm_type} = 'Q' (distinguishes Wikidata from OSM N/W/R)</li>
 *   <li>{@code data_sources} = ARRAY['wikidata']</li>
 * </ul>
 * <p>
 * Completeness scoring: Wikidata items with coordinates, labels, descriptions, and images
 * receive a base score of 40-70 depending on the data available.
 */
@Slf4j
@Component
public class WikidataPoiProcessor implements ItemProcessor<WikidataRawPoi, PoiEntity> {

    private static final long LOG_EVERY_N_ITEMS = 200;

    private final ObjectMapper jackson = new ObjectMapper();
    private final AtomicLong processedCount = new AtomicLong(0);

    /**
     * Converts a Wikidata Q-ID to a negative long for use as osm_id.
     * Example: "Q12345" → -12345, "Q42" → -42
     */
    static long qidToLong(String qid) {
        qid = normalizeQid(qid);
        if (qid == null || !qid.startsWith("Q")) return 0;
        try {
            return -Long.parseLong(qid.substring(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String normalizeQid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int lastSlash = raw.lastIndexOf('/');
        String candidate = lastSlash >= 0 ? raw.substring(lastSlash + 1) : raw;
        return candidate.startsWith("Q") ? candidate : raw;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────


    @Override
    public PoiEntity process(WikidataRawPoi poi) throws Exception {
        String normalizedQid = normalizeQid(poi.getQid());
        long osmId = qidToLong(normalizedQid);
        short score = calculateCompleteness(poi);
        String attributesJson = jackson.writeValueAsString(buildAttributes(poi, normalizedQid));

        long current = processedCount.incrementAndGet();
        if (current % LOG_EVERY_N_ITEMS == 0) {
            log.info(
                    "Wikidata import progress | stage=process | processed={} | last_qid={} | category={} | subcategory={}",
                    current,
                    poi.getQid(),
                    poi.getCategory(),
                    poi.getSubcategory());
        }

        return PoiEntity.builder()
                .osmId(osmId)
                .osmType("Q")
                .wikidataId(normalizedQid)
                .nameTr(poi.getLabelTr() != null ? poi.getLabelTr() : "")
                .nameEn(poi.getLabelEn())
                .category(poi.getCategory())
                .subcategory(poi.getSubcategory())
                .lat(poi.getLat())
                .lon(poi.getLon())
                .boundaryWkt(null)  // Wikidata items are point features
                .completenessScore(score)
                .attributesJson(attributesJson)
                .build();
    }

    private Map<String, Object> buildAttributes(WikidataRawPoi poi, String normalizedQid) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("wikidata_id", normalizedQid);
        attrs.put("source", "wikidata");
        if (poi.getLabelTr() != null) attrs.put("name", poi.getLabelTr());
        if (poi.getLabelEn() != null) attrs.put("name:en", poi.getLabelEn());
        if (poi.getDescriptionTr() != null) attrs.put("description", poi.getDescriptionTr());
        if (poi.getDescriptionEn() != null) attrs.put("description:en", poi.getDescriptionEn());
        if (poi.getImageUrl() != null) attrs.put("image", poi.getImageUrl());
        if (poi.getInstanceOfQid() != null) attrs.put("instance_of", poi.getInstanceOfQid());
        if (poi.getWikipediaEnTitle() != null) attrs.put("wikipedia", "en:" + poi.getWikipediaEnTitle());
        if (poi.getWikipediaTrTitle() != null) attrs.put("wikipedia:tr", "tr:" + poi.getWikipediaTrTitle());
        return attrs;
    }

    /**
     * Completeness score for Wikidata items:
     * Base 20 (location) + 20 (name) + 10 (English name) + 10 (description) + 10 (image)
     */
    private short calculateCompleteness(WikidataRawPoi poi) {
        int score = 20; // always has coordinates
        if (poi.getLabelTr() != null && !poi.getLabelTr().isBlank()) score += 20;
        if (poi.getLabelEn() != null && !poi.getLabelEn().isBlank()) score += 10;
        if (poi.getDescriptionTr() != null || poi.getDescriptionEn() != null) score += 10;
        if (poi.getImageUrl() != null && !poi.getImageUrl().isBlank()) score += 10;
        return (short) Math.min(score, 100);
    }
}