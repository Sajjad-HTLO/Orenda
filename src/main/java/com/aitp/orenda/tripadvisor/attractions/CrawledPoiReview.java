package com.aitp.orenda.tripadvisor.attractions;

import lombok.Builder;

import java.util.Map;

/**
 * A single traveler review crawled from a Tripadvisor attraction product page.
 * Captures the reviewer, overall bubble rating, optional title, comment text,
 * review date and any per-aspect sub-ratings that the page exposes. Attraction
 * products generally only expose an overall rating (no aspects), so the
 * {@code aspects} map is usually empty.
 */
@Builder
public record CrawledPoiReview(
        long tripadvisorId,
        String reviewerName,
        Integer rating,
        String title,
        String comment,
        String reviewDate,
        Map<String, Integer> aspects,
        String sourceUrl
) {
}
