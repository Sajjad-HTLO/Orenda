package com.aitp.orenda.trip;

import com.aitp.orenda.model.PoiResponse;
import com.aitp.orenda.weather.WeatherResponse;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-trip weather context derived from the Open-Meteo daily forecast. Provides
 * rain/outdoor-suitability lookups per trip date so the planner can prefer indoor
 * venues on rainy days and outdoor venues on clear days. Never throws: when the
 * forecast is unavailable every query degrades to neutral.
 */
public record TripWeather(List<WeatherResponse.DailyForecast> days) {

    public static TripWeather empty() {
        return new TripWeather(List.of());
    }

    public boolean isRainy(String date) {
        Optional<WeatherResponse.DailyForecast> day = byDate(date);
        if (day.isEmpty()) {
            return false;
        }
        WeatherResponse.DailyForecast d = day.get();
        return d.precipitation() >= 2.0 || isRainCode(d.weatherCode()) || isSnowCode(d.weatherCode());
    }

    public boolean isOutdoorGood(String date) {
        Optional<WeatherResponse.DailyForecast> day = byDate(date);
        if (day.isEmpty()) {
            return true;
        }
        int code = day.get().weatherCode();
        return code == 0 || code == 1 || code == 2;
    }

    public String description(String date) {
        Optional<WeatherResponse.DailyForecast> day = byDate(date);
        if (day.isEmpty()) {
            return "Weather forecast unavailable";
        }
        WeatherResponse.DailyForecast d = day.get();
        return d.description() + ", " + Math.round(d.maxTemp()) + "°C";
    }

    public Optional<WeatherResponse.DailyForecast> byDate(String date) {
        if (days == null) {
            return Optional.empty();
        }
        return days.stream().filter(d -> d.date().equals(date)).findFirst();
    }

    private static boolean isRainCode(int code) {
        return (code >= 51 && code <= 67)
                || (code >= 80 && code <= 82)
                || code == 95 || code == 96 || code == 99;
    }

    /**
     * Snow codes (71–77, 85–86) are treated like rain for planning purposes:
     * a snowy day should lean indoors and avoid open-roof venues just like a
     * rainy one.
     */
    private static boolean isSnowCode(int code) {
        return (code >= 71 && code <= 77) || code == 85 || code == 86;
    }

    /**
     * Whether a POI is best experienced indoors (museums, galleries, restaurants,
     * malls, nightlife, etc.) — used to fit venues to rainy days.
     */
    public static boolean isIndoor(PoiResponse poi) {
        String s = poi.getSubcategory() == null ? "" : poi.getSubcategory();
        return List.of("museum", "gallery", "library", "aquarium", "restaurant", "cafe", "bar",
                        "pub", "fast_food", "food_court", "mall", "department_store", "shop",
                        "spa", "theatre", "arts_centre", "cinema", "nightclub", "casino", "hotel")
                .contains(s);
    }

    /**
     * Whether a POI is best experienced outdoors (parks, viewpoints, ruins,
     * palaces, beaches, etc.) — used to fit venues to clear days.
     */
    public static boolean isOutdoor(PoiResponse poi) {
        String s = poi.getSubcategory() == null ? "" : poi.getSubcategory();
        return List.of("park", "garden", "nature_reserve", "peak", "waterfall", "beach",
                        "viewpoint", "zoo", "theme_park", "water_park", "playground", "hiking",
                        "ruins", "archaeological", "castle", "fort", "citadel", "marina",
                        "stadium", "sports_centre", "memorial")
                .contains(s);
    }

    /**
     * Whether a POI is effectively unsheltered — an open-roof / open-air venue the
     * traveler would get soaked at in the rain. True for every outdoor POI plus
     * any venue that is tagged as un-covered or whose name signals a rooftop or
     * open-air terrace (e.g. "Roof Bar", "Rooftop Terrace"). Used to exclude
     * open-roof venues on rainy days.
     */
    public static boolean isOpenRoof(PoiResponse poi) {
        if (isOutdoor(poi)) {
            return true;
        }
        Map<String, Object> attrs = poi.getAttributes();
        if (attrs != null) {
            if (isValue("no", attrs.get("covered")) || isValue("no", attrs.get("roof"))) {
                return true;
            }
            if (isValue("yes", attrs.get("open_air")) || isValue("yes", attrs.get("rooftop"))) {
                return true;
            }
        }
        String name = (poi.getNameTr() == null ? "" : poi.getNameTr().toLowerCase())
                + " " + (poi.getNameEn() == null ? "" : poi.getNameEn().toLowerCase());
        return name.contains("rooftop") || name.contains("roof terrace")
                || name.contains("roof top") || name.contains(" roof ")
                || name.contains("open-air") || name.contains("open air");
    }

    private static boolean isValue(String expected, Object actual) {
        return actual != null && expected.equalsIgnoreCase(String.valueOf(actual));
    }
}