package com.aitp.orenda.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;

/**
 * User-submitted feedback for a POI (closed, inaccurate, moved, duplicate, other).
 */
@Data
@Builder
public class FeedbackRequest {

    /**
     * UUID of the POI being reported.
     */
    @NotBlank(message = "poiId must not be blank")
    private String poiId;

    /**
     * Feedback type (CLOSED, INACCURATE, MOVED, DUPLICATE, OTHER).
     */
    @NotNull(message = "type must not be null")
    private FeedbackType type;

    /**
     * Free-text details (optional; null when absent).
     */
    private String details;

    /**
     * Client-side session ID for anonymous user tracking (optional; null when absent).
     */
    private String sessionId;

    /**
     * Corrected latitude — only relevant when type = MOVED (optional; null when absent).
     */
    private Double newLat;

    /**
     * Corrected longitude — only relevant when type = MOVED (optional; null when absent).
     */
    private Double newLon;
}
