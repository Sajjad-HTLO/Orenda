package com.aitp.orenda.review;

import java.time.Instant;
import java.util.UUID;

/**
 * Public review payload returned to clients.
 */
public record PoiReviewResponse(
        UUID id,
        UUID poiId,
        String travelerName,
        int rating,
        String title,
        String comment,
        Instant createdAt
) {

    static PoiReviewResponse from(PoiReview review) {
        return new PoiReviewResponse(
                review.id(),
                review.poiId(),
                review.travelerName(),
                review.rating(),
                review.title(),
                review.comment(),
                review.createdAt());
    }
}