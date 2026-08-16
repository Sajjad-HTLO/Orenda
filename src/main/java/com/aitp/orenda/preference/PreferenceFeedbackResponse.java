package com.aitp.orenda.preference;

import com.aitp.orenda.model.PoiResponse;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Result of processing a preference-feedback event: confirmation, the updated
 * per-category weights, a natural-language insight about the traveler, and (for
 * {@code FIND_SIMILAR}) similar POIs to suggest next.
 */
@Data
@Builder
public class PreferenceFeedbackResponse {

    private boolean accepted;

    private String message;

    /**
     * Learned weights per {@link PreferenceCategory} (0..1), e.g.
     * {@code {CULTURE: 0.91, FOOD: 0.83, SHOPPING: 0.21, NIGHTLIFE: 0.08}}.
     */
    private Map<String, Double> updatedWeights;

    /**
     * e.g. "I noticed you tend to prefer cultural experiences and local food.
     * You seem to skip nightlife and shopping. I've adjusted your
     * recommendations accordingly."
     */
    private String insight;

    /**
     * Present only for {@link FeedbackReason#FIND_SIMILAR}: POIs in the same
     * category near the reported one.
     */
    private List<PoiResponse> similarPois;
}