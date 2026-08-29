package com.aitp.orenda.batch;

import com.aitp.orenda.model.OsmPoi;
import com.aitp.orenda.model.PoiEntity;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OsmPoiItemProcessorTest {

    private final OsmPoiItemProcessor processor = new OsmPoiItemProcessor();

    private Map<String, String> tags(String... keyValues) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            tags.put(keyValues[i], keyValues[i + 1]);
        }
        return tags;
    }

    @Test
    void maps_osm_tags_to_poi_entity() throws Exception {
        OsmPoi poi = OsmPoi.builder()
                .osmId(123456789L)
                .osmType('N')
                .lat(41.0082)
                .lon(28.9784)
                .tags(tags(
                        "name", "Topkapı Palace",
                        "name:en", "Topkapi Palace",
                        "tourism", "museum",
                        "wikidata", "Q201297",
                        "opening_hours", "09:00-17:00",
                        "phone", "+90 212 512 04 80"))
                .build();

        PoiEntity entity = processor.process(poi);

        assertThat(entity.getOsmId()).isEqualTo(123456789L);
        assertThat(entity.getOsmType()).isEqualTo("N");
        assertThat(entity.getNameTr()).isEqualTo("Topkapı Palace");
        assertThat(entity.getNameEn()).isEqualTo("Topkapi Palace");
        assertThat(entity.getCategory()).isEqualTo("culture");
        assertThat(entity.getSubcategory()).isEqualTo("museum");
        assertThat(entity.getWikidataId()).isEqualTo("Q201297");
        assertThat(entity.getLat()).isEqualTo(41.0082);
        assertThat(entity.getLon()).isEqualTo(28.9784);
        // name(20) + name:en(10) + opening_hours(15) + phone(10) + wikidata(10) + base(20) = 85
        assertThat(entity.getCompletenessScore()).isEqualTo((short) 85);
        assertThat(entity.getAttributesJson()).contains("\"wikidata\":\"Q201297\"")
                .contains("\"opening_hours\":\"09:00-17:00\"");
    }

    @Test
    void keeps_all_tags_in_attributes_jsonb() throws Exception {
        OsmPoi poi = OsmPoi.builder()
                .osmId(1L)
                .osmType('N')
                .lat(41.0)
                .lon(29.0)
                .tags(tags("name", "A", "cuisine", "kebab", "diet:vegetarian", "yes", "website", ""))
                .build();

        PoiEntity entity = processor.process(poi);

        assertThat(entity.getAttributesJson()).contains("\"cuisine\":\"kebab\"")
                .contains("\"diet:vegetarian\":\"yes\"")
                .doesNotContain("website"); // blank values are dropped
    }

    @Test
    void sparser_pois_get_lower_completeness() throws Exception {
        OsmPoi poi = OsmPoi.builder()
                .osmId(2L)
                .osmType('W')
                .lat(41.0)
                .lon(29.0)
                .tags(tags("name", "Only a name"))
                .build();

        PoiEntity entity = processor.process(poi);

        assertThat(entity.getCompletenessScore()).isEqualTo((short) 40); // base 20 + name 20
    }
}