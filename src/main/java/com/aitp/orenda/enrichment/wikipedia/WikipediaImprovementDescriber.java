package com.aitp.orenda.enrichment.wikipedia;

import com.aitp.orenda.enrichment.PoiEnrichmentCandidate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class WikipediaImprovementDescriber {

    private WikipediaImprovementDescriber() {
    }

    static ImprovementDetails describe(
            PoiEnrichmentCandidate poi,
            Map<String, Object> updates,
            short previousScore,
            short newScore,
            int sourceFieldsStored) {

        Map<String, Object> existing = poi.getAttributes() != null ? poi.getAttributes() : Map.of();

        int summaries = isNewContent(existing, updates, "wikipedia_summary") ? 1 : 0;
        int descriptions = isNewContent(existing, updates, "wikipedia_description") ? 1 : 0;
        int images = isNewContent(existing, updates, "wikipedia_image_url") ? 1 : 0;
        int urls = isNewContent(existing, updates, "wikipedia_url") ? 1 : 0;
        int titles = isNewContent(existing, updates, "wikipedia_title") ? 1 : 0;

        List<String> parts = new ArrayList<>();
        if (summaries > 0) parts.add(summaries == 1 ? "1 summary" : summaries + " summaries");
        if (descriptions > 0) parts.add(descriptions == 1 ? "1 description" : descriptions + " descriptions");
        if (images > 0) parts.add(images == 1 ? "1 image" : images + " images");
        if (urls > 0) parts.add(urls == 1 ? "1 url" : urls + " urls");
        if (titles > 0) parts.add(titles == 1 ? "1 title" : titles + " titles");

        String humanReadable = parts.isEmpty() ? "no new fields (metadata refreshed)" : String.join(", ", parts);

        return new ImprovementDetails(
                summaries,
                descriptions,
                images,
                urls,
                sourceFieldsStored,
                previousScore,
                newScore,
                humanReadable
        );
    }

    private static boolean isNewContent(Map<String, Object> existing, Map<String, Object> updates, String key) {
        if (!updates.containsKey(key)) return false;
        Object newVal = updates.get(key);
        if (newVal == null || newVal.toString().isBlank()) return false;
        Object oldVal = existing.get(key);
        return oldVal == null || oldVal.toString().isBlank();
    }

    record ImprovementDetails(
            int summariesAdded,
            int descriptionsAdded,
            int imagesAdded,
            int urlsAdded,
            int sourceFieldsStored,
            short previousScore,
            short newScore,
            String humanReadable
    ) {
    }
}
