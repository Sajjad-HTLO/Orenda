package com.aitp.orenda.preference;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * Immediate preference feedback for a suggested POI: a reaction (like / dislike /
 * love / not interested / rated) plus an optional reason ("too expensive",
 * "too far", "find something similar", …).
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PreferenceFeedbackRequest {

    @NotBlank(message = "poiId must not be blank")
    private String poiId;

    @NotBlank(message = "sessionId must not be blank")
    private String sessionId;

    @NotNull(message = "reaction must not be null")
    private PreferenceReaction reaction;

    /**
     * 1–5 rating; required (and only meaningful) when {@code reaction = RATED}.
     */
    @Min(value = 1, message = "rating must be between 1 and 5")
    @Max(value = 5, message = "rating must be between 1 and 5")
    private Integer rating;

    private FeedbackReason reason;

    private String details;
}