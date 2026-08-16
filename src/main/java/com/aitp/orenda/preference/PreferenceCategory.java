package com.aitp.orenda.preference;

import com.aitp.orenda.model.PoiResponse;

/**
 * The learned preference dimensions tracked per traveler. Each maps onto the
 * normalized {@code poi.category}/{@code poi.subcategory} values produced by
 * {@link com.aitp.orenda.mapping.CategoryMapper}, so a user's weight in a
 * dimension (e.g. CULTURE 0.91) can be applied directly to candidate POIs.
 */
public enum PreferenceCategory {
    CULTURE, FOOD, SHOPPING, NIGHTLIFE, NATURE, LEISURE, WELLNESS, ATTRACTION, ACCOMMODATION, OTHER;

    /**
     * Maps a POI to its preference dimension.
     */
    public static PreferenceCategory forPoi(PoiResponse poi) {
        String category = poi.getCategory() == null ? "" : poi.getCategory();
        return switch (category) {
            case "culture", "historic" -> CULTURE;
            case "food_drink" -> FOOD;
            case "shopping" -> SHOPPING;
            case "entertainment" -> NIGHTLIFE;
            case "nature" -> NATURE;
            case "leisure" -> LEISURE;
            case "wellness" -> WELLNESS;
            case "attraction" -> ATTRACTION;
            case "accommodation" -> ACCOMMODATION;
            default -> OTHER;
        };
    }

    /**
     * Human-friendly plural label used in the "I noticed you tend to prefer…"
     * message.
     */
    public String label() {
        return switch (this) {
            case CULTURE -> "cultural experiences";
            case FOOD -> "local food";
            case SHOPPING -> "shopping";
            case NIGHTLIFE -> "nightlife";
            case NATURE -> "outdoor nature spots";
            case LEISURE -> "family-friendly leisure activities";
            case WELLNESS -> "wellness and relaxation";
            case ATTRACTION -> "sightseeing attractions";
            case ACCOMMODATION -> "staying options";
            case OTHER -> "miscellaneous experiences";
        };
    }
}