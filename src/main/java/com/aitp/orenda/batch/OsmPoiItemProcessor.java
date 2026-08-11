package com.aitp.orenda.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aitp.orenda.mapping.CategoryMapper;
import com.aitp.orenda.mapping.CompletenessCalculator;
import com.aitp.orenda.model.OsmPoi;
import com.aitp.orenda.model.PoiEntity;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class OsmPoiItemProcessor implements ItemProcessor<OsmPoi, PoiEntity> {

    private final CategoryMapper categoryMapper       = new CategoryMapper();
    private final CompletenessCalculator completeness = new CompletenessCalculator();
    private final ObjectMapper jackson                = new ObjectMapper();

    @Override
    public PoiEntity process(OsmPoi poi) throws Exception {
        Map<String, String> tags = poi.getTags();
        String[] catSub = categoryMapper.map(tags);
        short score = completeness.calculate(poi);

        return PoiEntity.builder()
            .osmId(poi.getOsmId())
            .osmType(String.valueOf(poi.getOsmType()))
            .wikidataId(tags.get("wikidata"))
            .nameTr(tags.getOrDefault("name", ""))
            .nameEn(tags.get("name:en"))
            .category(catSub[0])
            .subcategory(catSub.length > 1 ? catSub[1] : null)
            .lat(poi.getLat())
            .lon(poi.getLon())
            .boundaryWkt(poi.getBoundaryWkt())
            .completenessScore(score)
            .attributesJson(jackson.writeValueAsString(buildAttributes(tags)))
            .build();
    }

    // All OSM tags go into attributes JSONB for flexible querying.
    private Map<String, Object> buildAttributes(Map<String, String> tags) {
        Map<String, Object> attrs = new HashMap<>(tags.size() * 2);
        tags.forEach((k, v) -> {
            if (v != null && !v.isBlank()) attrs.put(k, v);
        });
        return attrs;
    }
}