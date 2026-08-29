package com.aitp.orenda.review;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body for POST /api/pois/{id}/reviews — a new traveler review/rating.
 */
public record PoiReviewRequest(
        @NotNull(message = "rating must not be null")
        @Min(value = 1, message = "rating must be between 1 and 5")
        @Max(value = 5, message = "rating must be between 1 and 5")
        Integer rating,

        @Size(max = 200, message = "title must be at most 200 characters")
        String title,

        @Size(max = 4000, message = "comment must be at most 4000 characters")
        String comment
) {
}