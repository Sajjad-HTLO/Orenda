package com.aitp.orenda.trip;

import com.aitp.orenda.model.PoiResponse;
import com.aitp.orenda.weather.WeatherResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TripWeatherTest {

    private static final WeatherResponse.DailyForecast CLEAR = day("2026-08-15", 0, 0.0);
    private static final WeatherResponse.DailyForecast RAIN_CODE = day("2026-08-16", 61, 0.0);
    private static final WeatherResponse.DailyForecast RAIN_PRECIP = day("2026-08-17", 2, 6.4);
    private static final WeatherResponse.DailyForecast DRIZZLE = day("2026-08-18", 55, 0.0);

    // ── Rain / outdoor-suitability lookups ──────────────────────────────────

    @Test
    void isRainy_treats_precipitation_and_wmo_rain_codes() {
        TripWeather w = new TripWeather(List.of(CLEAR, RAIN_CODE, RAIN_PRECIP, DRIZZLE));
        assertThat(w.isRainy("2026-08-15")).isFalse();
        assertThat(w.isRainy("2026-08-16")).isTrue();   // code 61
        assertThat(w.isRainy("2026-08-17")).isTrue();   // precipitation >= 2.0
        assertThat(w.isRainy("2026-08-18")).isTrue();   // code 55 (51..67)
    }

    @Test
    void isRainy_degrades_to_false_when_no_forecast() {
        TripWeather empty = TripWeather.empty();
        assertThat(empty.isRainy("2026-08-15")).isFalse();
        assertThat(empty.isOutdoorGood("2026-08-15")).isTrue();
    }

    @Test
    void isOutdoorGood_only_for_clear_sky_codes() {
        TripWeather w = new TripWeather(List.of(
                day("d1", 0, 0.0), day("d2", 1, 0.0), day("d3", 2, 0.0), day("d4", 3, 0.0)));
        assertThat(w.isOutdoorGood("d1")).isTrue();
        assertThat(w.isOutdoorGood("d2")).isTrue();
        assertThat(w.isOutdoorGood("d3")).isTrue();
        assertThat(w.isOutdoorGood("d4")).isFalse(); // overcast
    }

    // ── Indoor / outdoor classification ─────────────────────────────────────

    @Test
    void isIndoor_covers_sheltered_venue_types() {
        assertThat(TripWeather.isIndoor(poi("culture", "museum"))).isTrue();
        assertThat(TripWeather.isIndoor(poi("food_drink", "restaurant"))).isTrue();
        assertThat(TripWeather.isIndoor(poi("shopping", "mall"))).isTrue();
        assertThat(TripWeather.isIndoor(poi("nature", "park"))).isFalse();
    }

    @Test
    void isOutdoor_covers_unsheltered_venue_types() {
        assertThat(TripWeather.isOutdoor(poi("nature", "park"))).isTrue();
        assertThat(TripWeather.isOutdoor(poi("historic", "ruins"))).isTrue();
        assertThat(TripWeather.isOutdoor(poi("attraction", "viewpoint"))).isTrue();
        assertThat(TripWeather.isOutdoor(poi("culture", "museum"))).isFalse();
    }

    // ── Open-roof detection ─────────────────────────────────────────────────

    @Test
    void isOpenRoof_outdoor_categories_are_always_open_roof() {
        assertThat(TripWeather.isOpenRoof(poi("nature", "park"))).isTrue();
        assertThat(TripWeather.isOpenRoof(poi("leisure", "beach"))).isTrue();
        assertThat(TripWeather.isOpenRoof(poi("attraction", "viewpoint"))).isTrue();
    }

    @Test
    void isOpenRoof_detects_uncovered_osmtags() {
        assertThat(TripWeather.isOpenRoof(poi("food_drink", "restaurant", Map.of("covered", "no")))).isTrue();
        assertThat(TripWeather.isOpenRoof(poi("food_drink", "restaurant", Map.of("roof", "no")))).isTrue();
        assertThat(TripWeather.isOpenRoof(poi("food_drink", "restaurant", Map.of("open_air", "yes")))).isTrue();
        assertThat(TripWeather.isOpenRoof(poi("food_drink", "restaurant", Map.of("rooftop", "yes")))).isTrue();
    }

    @Test
    void isOpenRoof_detects_rooftop_and_open_air_names() {
        assertThat(TripWeather.isOpenRoof(poi("food_drink", "bar", "Rooftop Bar"))).isTrue();
        assertThat(TripWeather.isOpenRoof(poi("food_drink", "restaurant", "Museum Cafe Roof Terrace"))).isTrue();
        assertThat(TripWeather.isOpenRoof(poi("food_drink", "restaurant", "Open Air Bazaar Cafe"))).isTrue();
    }

    @Test
    void isOpenRoof_does_not_flag_sheltered_or_outdoor_seating_venues() {
        // Outdoor seating still has indoor shelter — never treated as open-roof.
        assertThat(TripWeather.isOpenRoof(poi("food_drink", "restaurant", Map.of("outdoor_seating", "yes")))).isFalse();
        assertThat(TripWeather.isOpenRoof(poi("culture", "museum"))).isFalse();
        assertThat(TripWeather.isOpenRoof(poi("food_drink", "restaurant", "Topkapı Kebab House"))).isFalse();
        assertThat(TripWeather.isOpenRoof(poi("historic", "palace", "Topkapı Palace"))).isFalse();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static PoiResponse poi(String category, String subcategory) {
        return poi(category, subcategory, "Test Place", Map.of());
    }

    private static PoiResponse poi(String category, String subcategory, String name) {
        return poi(category, subcategory, name, Map.of());
    }

    private static PoiResponse poi(String category, String subcategory, Map<String, Object> attrs) {
        return poi(category, subcategory, "Test Place", attrs);
    }

    private static PoiResponse poi(String category, String subcategory, String name, Map<String, Object> attrs) {
        return PoiResponse.builder()
                .id("11111111-1111-1111-1111-111111111111")
                .nameTr(name)
                .category(category)
                .subcategory(subcategory)
                .lat(41.01)
                .lon(28.98)
                .completenessScore(80)
                .attributes(attrs)
                .build();
    }

    private static WeatherResponse.DailyForecast day(String date, int code, double precip) {
        return new WeatherResponse.DailyForecast(date, 26, 18, precip, 12, code, "forecast");
    }
}
