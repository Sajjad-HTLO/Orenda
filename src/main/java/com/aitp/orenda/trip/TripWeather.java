package com.aitp.orenda.trip;

import com.aitp.orenda.model.PoiResponse;
import com.aitp.orenda.weather.WeatherResponse;

import java.util.List;
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
        return d.precipitation() >= 2.0 || isRainCode(d.weatherCode());
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
}