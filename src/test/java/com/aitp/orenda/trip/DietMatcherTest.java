package com.aitp.orenda.trip;

import com.aitp.orenda.model.PoiResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DietMatcherTest {

    @Test
    void none_diet_matches_everything() {
        assertThat(DietMatcher.matches(restaurant(Map.of()), TripEnums.Diet.NONE)).isTrue();
        assertThat(DietMatcher.matches(restaurant(Map.of()), null)).isTrue();
    }

    @Test
    void vegetarian_matches_osm_diet_tags() {
        assertThat(DietMatcher.matches(restaurant(Map.of("diet:vegetarian", "yes")),
                TripEnums.Diet.VEGETARIAN)).isTrue();
        assertThat(DietMatcher.matches(restaurant(Map.of("vegetarian", "yes")),
                TripEnums.Diet.VEGETARIAN)).isTrue();
        assertThat(DietMatcher.matches(restaurant(Map.of("cuisine", "kebab")),
                TripEnums.Diet.VEGETARIAN)).isFalse();
    }

    @Test
    void vegan_matches_diet_vegan_tag() {
        assertThat(DietMatcher.matches(restaurant(Map.of("diet:vegan", "yes")),
                TripEnums.Diet.VEGAN)).isTrue();
        assertThat(DietMatcher.matches(restaurant(Map.of("cuisine", "steak")),
                TripEnums.Diet.VEGAN)).isFalse();
    }

    @Test
    void halal_matches_halal_tag_or_cuisine() {
        assertThat(DietMatcher.matches(restaurant(Map.of("diet:halal", "yes")),
                TripEnums.Diet.HALAL)).isTrue();
        assertThat(DietMatcher.matches(restaurant(Map.of("cuisine", "halal kebab")),
                TripEnums.Diet.HALAL)).isTrue();
        assertThat(DietMatcher.matches(restaurant(Map.of("cuisine", "pizza")),
                TripEnums.Diet.HALAL)).isFalse();
    }

    @Test
    void gluten_free_and_lactose_free_match_their_tags() {
        assertThat(DietMatcher.matches(restaurant(Map.of("diet:gluten_free", "yes")),
                TripEnums.Diet.GLUTEN_FREE)).isTrue();
        assertThat(DietMatcher.matches(restaurant(Map.of("diet:lactose-free", "yes")),
                TripEnums.Diet.LACTOSE_FREE)).isTrue();
        assertThat(DietMatcher.matches(restaurant(Map.of("cuisine", "kebab")),
                TripEnums.Diet.GLUTEN_FREE)).isFalse();
    }

    @Test
    void missing_attributes_never_match_a_restriction() {
        assertThat(DietMatcher.matches(restaurant(Map.of()), TripEnums.Diet.VEGAN)).isFalse();
    }

    private PoiResponse restaurant(Map<String, Object> attrs) {
        return PoiResponse.builder()
                .id("11111111-1111-1111-1111-111111111111")
                .nameTr("Test Restaurant")
                .category("food_drink")
                .subcategory("restaurant")
                .lat(41.01)
                .lon(28.98)
                .completenessScore(70)
                .attributes(attrs)
                .build();
    }
}
