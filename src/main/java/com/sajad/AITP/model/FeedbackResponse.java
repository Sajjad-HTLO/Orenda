package com.sajad.AITP.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * Response after processing user feedback for a POI.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FeedbackResponse {

    /**
     * Whether the feedback was accepted and processed.
     */
    private boolean accepted;

    /**
     * Human-readable message (e.g. "Feedback received — POI marked as closed").
     */
    private String message;

    /**
     * The nearest alternative POI suggested as replacement. Null when no alternative was found nearby.
     */
    private PoiResponse alternativePoi;
}
