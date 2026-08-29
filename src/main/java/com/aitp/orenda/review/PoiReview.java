package com.aitp.orenda.review;

import java.time.Instant;
import java.util.UUID;

/**
 * A traveler review/rating snapshot for a POI.
 */
public record PoiReview(
        UUID id,
        UUID poiId,
        UUID userId,
        String travelerName,
        int rating,
        String title,
        String comment,
        Instant createdAt
) {
}