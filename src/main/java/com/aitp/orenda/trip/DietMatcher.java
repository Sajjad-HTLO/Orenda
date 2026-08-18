package com.aitp.orenda.trip;

import com.aitp.orenda.model.PoiResponse;

import java.util.Map;

/**
 * Matches a POI (typically a restaurant) against a traveler's dietary
 * restriction using the OSM dietary tags stored in the POI's attributes JSONB
 * (e.g. {@code diet:vegetarian=yes}, {@code diet:halal=yes}). {@code NONE} and
 * {@code null} diets match everything.
 */
public final class DietMatcher {

    private DietMatcher() {
    }

    /**
     * Whether the POI fits the given diet. Unrestricted diets always match;
     * a missing diet attribute never matches a restricted diet.
     */
    public static boolean matches(PoiResponse poi, TripEnums.Diet diet) {
        if (diet == null || diet == TripEnums.Diet.NONE) {
            return true;
        }
        Map<String, Object> attrs = poi == null ? null : poi.getAttributes();
        if (attrs == null) {
            return false;
        }
        return switch (diet) {
            case VEGETARIAN -> yes(attrs.get("diet:vegetarian")) || yes(attrs.get("vegetarian"));
            case VEGAN -> yes(attrs.get("diet:vegan")) || yes(attrs.get("vegan"));
            case HALAL -> yes(attrs.get("diet:halal")) || yes(attrs.get("halal"))
                    || cuisineContains(attrs, "halal");
            case GLUTEN_FREE -> yes(attrs.get("diet:gluten_free")) || yes(attrs.get("diet:gluten-free"))
                    || yes(attrs.get("gluten_free"));
            case LACTOSE_FREE -> yes(attrs.get("diet:lactose_free")) || yes(attrs.get("diet:lactose-free"));
            default -> true;
        };
    }

    /**
     * Human-readable label for a diet, e.g. "vegetarian", "halal", "gluten-free".
     */
    public static String description(TripEnums.Diet diet) {
        return switch (diet) {
            case VEGETARIAN -> "vegetarian";
            case VEGAN -> "vegan";
            case HALAL -> "halal";
            case GLUTEN_FREE -> "gluten-free";
            case LACTOSE_FREE -> "lactose-free";
            default -> "any";
        };
    }

    private static boolean yes(Object value) {
        return value != null && "yes".equalsIgnoreCase(String.valueOf(value));
    }

    private static boolean cuisineContains(Map<String, Object> attrs, String token) {
        Object cuisine = attrs.get("cuisine");
        return cuisine != null && String.valueOf(cuisine).toLowerCase().contains(token);
    }
}
