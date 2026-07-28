package com.sajad.AITP.muzegov;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sajad.AITP.model.PoiEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts a {@link MuzeGovTrRawPoi} into a {@link PoiEntity} for PostgreSQL insertion.
 * <p>
 * Naming convention for muze.gov.tr-sourced POIs:
 * <ul>
 *   <li>{@code osm_id} = negative hash of SectionId (e.g., "ATM01" → negative number)</li>
 *   <li>{@code osm_type} = 'G' (Government source)</li>
 *   <li>{@code data_sources} = ARRAY['muzegov']</li>
 * </ul>
 */
@Slf4j
@Component
public class MuzeGovTrProcessor implements ItemProcessor<MuzeGovTrRawPoi, PoiEntity> {

    private final ObjectMapper jackson = new ObjectMapper();

    /**
     * Converts a SectionId like "ATM01" into a negative long for use as osm_id.
     * Uses hash-based approach: convert chars to numeric, ensure negative.
     */
    static long sectionIdToLong(String sectionId) {
        if (sectionId == null) return 0;
        long hash = 0;
        for (int i = 0; i < sectionId.length(); i++) {
            hash = hash * 31 + sectionId.charAt(i);
        }
        return -Math.abs(hash);
    }

    @Override
    public PoiEntity process(MuzeGovTrRawPoi poi) throws Exception {
        long osmId = sectionIdToLong(poi.getSectionId());
        short score = calculateCompleteness(poi);
        String attributesJson = jackson.writeValueAsString(buildAttributes(poi));

        return PoiEntity.builder()
                .osmId(osmId)
                .osmType("G")  // Government source
                .wikidataId(null)
                .nameTr(poi.getNameTr())
                .nameEn(null)
                .category("culture")
                .subcategory("museum")
                .lat(poi.getLat())
                .lon(poi.getLon())
                .boundaryWkt(null)
                .completenessScore(score)
                .attributesJson(attributesJson)
                .build();
    }

    private Map<String, Object> buildAttributes(MuzeGovTrRawPoi poi) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("source", "muzegov");
        attrs.put("section_id", poi.getSectionId());
        attrs.put("dist_id", poi.getDistId());
        attrs.put("name", poi.getNameTr());
        attrs.put("muzegov_detail_url",
                "https://muze.gov.tr/muze-detay?SectionId=" + poi.getSectionId()
                        + "&DistId=" + poi.getDistId());
        if (poi.getDescriptionTr() != null) {
            attrs.put("description", poi.getDescriptionTr());
        }
        return attrs;
    }

    private short calculateCompleteness(MuzeGovTrRawPoi poi) {
        int score = 20; // coordinates
        if (poi.getNameTr() != null && !poi.getNameTr().isBlank()) score += 25;
        if (poi.getDescriptionTr() != null && !poi.getDescriptionTr().isBlank()) score += 15;
        return (short) Math.min(score, 100);
    }
}