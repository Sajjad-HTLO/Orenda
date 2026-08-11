package com.aitp.orenda.enrichment.wikipedia;

public record WikipediaSummary(
        String lang,
        String title,
        String extract,
        String description,
        String pageUrl,
        String imageUrl
) {
}
