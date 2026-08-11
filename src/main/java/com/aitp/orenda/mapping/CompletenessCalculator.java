package com.aitp.orenda.mapping;

import com.aitp.orenda.model.OsmPoi;

import java.util.Map;

public class CompletenessCalculator {

    public short calculate(OsmPoi poi) {
        Map<String, String> tags = poi.getTags();
        int score = 20; // location is always present

        if (has(tags, "name"))                                        score += 20;
        if (has(tags, "name:en"))                                     score += 10;
        if (has(tags, "opening_hours"))                               score += 15;
        if (has(tags, "phone") || has(tags, "contact:phone"))        score += 10;
        if (has(tags, "website") || has(tags, "contact:website"))    score += 10;
        if (has(tags, "wikidata"))                                    score += 10;
        if (has(tags, "description") || has(tags, "description:en")) score += 5;

        return (short) Math.min(score, 100);
    }

    private boolean has(Map<String, String> tags, String key) {
        String v = tags.get(key);
        return v != null && !v.isBlank();
    }
}