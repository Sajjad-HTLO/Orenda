package com.aitp.orenda.mapping;

import java.util.List;
import java.util.Map;

public class CategoryMapper {

    private static final Map<String, String[]> TOURISM = Map.ofEntries(
        Map.entry("museum",        new String[]{"culture",        "museum"}),
        Map.entry("gallery",       new String[]{"culture",        "gallery"}),
        Map.entry("artwork",       new String[]{"culture",        "artwork"}),
        Map.entry("aquarium",      new String[]{"culture",        "aquarium"}),
        Map.entry("hotel",         new String[]{"accommodation",  "hotel"}),
        Map.entry("motel",         new String[]{"accommodation",  "motel"}),
        Map.entry("hostel",        new String[]{"accommodation",  "hostel"}),
        Map.entry("guest_house",   new String[]{"accommodation",  "guest_house"}),
        Map.entry("apartment",     new String[]{"accommodation",  "apartment"}),
        Map.entry("camp_site",     new String[]{"accommodation",  "camp_site"}),
        Map.entry("caravan_site",  new String[]{"accommodation",  "caravan_site"}),
        Map.entry("chalet",        new String[]{"accommodation",  "chalet"}),
        Map.entry("alpine_hut",    new String[]{"accommodation",  "alpine_hut"}),
        Map.entry("attraction",    new String[]{"attraction",     "attraction"}),
        Map.entry("viewpoint",     new String[]{"attraction",     "viewpoint"}),
        Map.entry("zoo",           new String[]{"leisure",        "zoo"}),
        Map.entry("theme_park",    new String[]{"leisure",        "theme_park"}),
        Map.entry("picnic_site",   new String[]{"leisure",        "picnic_site"}),
        Map.entry("spa",           new String[]{"wellness",       "spa"}),
        Map.entry("information",   new String[]{"service",        "information"})
    );

    private static final Map<String, String[]> AMENITY = Map.ofEntries(
        Map.entry("restaurant",      new String[]{"food_drink",    "restaurant"}),
        Map.entry("cafe",            new String[]{"food_drink",    "cafe"}),
        Map.entry("bar",             new String[]{"food_drink",    "bar"}),
        Map.entry("pub",             new String[]{"food_drink",    "pub"}),
        Map.entry("fast_food",       new String[]{"food_drink",    "fast_food"}),
        Map.entry("food_court",      new String[]{"food_drink",    "food_court"}),
        Map.entry("ice_cream",       new String[]{"food_drink",    "ice_cream"}),
        Map.entry("place_of_worship",new String[]{"culture",       "place_of_worship"}),
        Map.entry("theatre",         new String[]{"culture",       "theatre"}),
        Map.entry("arts_centre",     new String[]{"culture",       "arts_centre"}),
        Map.entry("library",         new String[]{"culture",       "library"}),
        Map.entry("cinema",          new String[]{"entertainment", "cinema"}),
        Map.entry("nightclub",       new String[]{"entertainment", "nightclub"}),
        Map.entry("marketplace",     new String[]{"shopping",      "marketplace"}),
        Map.entry("ferry_terminal",  new String[]{"transport",     "ferry_terminal"}),
        Map.entry("parking",         new String[]{"transport",     "parking"})
    );

    private static final Map<String, String[]> HISTORIC = Map.ofEntries(
        Map.entry("castle",              new String[]{"historic", "castle"}),
        Map.entry("ruins",               new String[]{"historic", "ruins"}),
        Map.entry("monument",            new String[]{"historic", "monument"}),
        Map.entry("memorial",            new String[]{"historic", "memorial"}),
        Map.entry("archaeological_site", new String[]{"historic", "archaeological_site"}),
        Map.entry("fort",                new String[]{"historic", "fort"}),
        Map.entry("citadel",             new String[]{"historic", "citadel"}),
        Map.entry("palace",              new String[]{"historic", "palace"}),
        Map.entry("amphitheatre",        new String[]{"historic", "amphitheatre"}),
        Map.entry("aqueduct",            new String[]{"historic", "aqueduct"}),
        Map.entry("battlefield",         new String[]{"historic", "battlefield"}),
        Map.entry("mosque",              new String[]{"historic", "mosque"}),
        Map.entry("church",              new String[]{"historic", "church"}),
        Map.entry("building",            new String[]{"historic", "building"})
    );

    private static final Map<String, String[]> LEISURE = Map.ofEntries(
        Map.entry("park",           new String[]{"nature",  "park"}),
        Map.entry("garden",         new String[]{"nature",  "garden"}),
        Map.entry("nature_reserve", new String[]{"nature",  "nature_reserve"}),
        Map.entry("beach_resort",   new String[]{"nature",  "beach"}),
        Map.entry("marina",         new String[]{"leisure", "marina"}),
        Map.entry("water_park",     new String[]{"leisure", "water_park"}),
        Map.entry("swimming_pool",  new String[]{"leisure", "swimming_pool"}),
        Map.entry("sports_centre",  new String[]{"leisure", "sports_centre"}),
        Map.entry("miniature_golf", new String[]{"leisure", "miniature_golf"})
    );

    private static final Map<String, String[]> NATURAL = Map.ofEntries(
        Map.entry("peak",          new String[]{"nature", "peak"}),
        Map.entry("waterfall",     new String[]{"nature", "waterfall"}),
        Map.entry("beach",         new String[]{"nature", "beach"}),
        Map.entry("cave_entrance", new String[]{"nature", "cave"}),
        Map.entry("hot_spring",    new String[]{"nature", "hot_spring"}),
        Map.entry("spring",        new String[]{"nature", "spring"}),
        Map.entry("volcano",       new String[]{"nature", "volcano"}),
        Map.entry("strait",        new String[]{"nature", "strait"})
    );

    private static final List<String> PRIORITY_KEYS =
        List.of("tourism", "amenity", "historic", "leisure", "natural", "shop", "sport");

    /**
     * OSM multi-values use ';' — keep the first token for indexed subcategory. Full tag stays in attributes JSONB.
     */
    static String normalizeSubcategory(String value) {
        if (value == null || value.isBlank()) return value;
        int sep = value.indexOf(';');
        return sep >= 0 ? value.substring(0, sep).trim() : value.trim();
    }

    public String[] map(Map<String, String> tags) {
        String v;
        if ((v = tags.get("tourism"))  != null && TOURISM.containsKey(v))  return TOURISM.get(v);
        if ((v = tags.get("amenity"))  != null && AMENITY.containsKey(v))  return AMENITY.get(v);
        if ((v = tags.get("historic")) != null && HISTORIC.containsKey(v)) return HISTORIC.get(v);
        if ((v = tags.get("leisure"))  != null && LEISURE.containsKey(v))  return LEISURE.get(v);
        if ((v = tags.get("natural"))  != null && NATURAL.containsKey(v))  return NATURAL.get(v);

        // Fallback: return raw key=value as category/subcategory
        for (String key : PRIORITY_KEYS) {
            v = tags.get(key);
            if (v != null) return new String[]{key, normalizeSubcategory(v)};
        }
        return new String[]{"other", "other"};
    }
}