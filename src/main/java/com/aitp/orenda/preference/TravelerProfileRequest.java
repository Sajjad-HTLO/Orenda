package com.aitp.orenda.preference;

import com.aitp.orenda.trip.TripEnums;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Short onboarding payload (≈5–8 questions). Establishes the long-term traveler
 * profile; a specific trip can override any of these via {@code TripPlanRequest}.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TravelerProfileRequest {

    @NotBlank(message = "sessionId must not be blank")
    private String sessionId;

    /**
     * Which of the traveler's companions are children (family trips).
     */
    private Integer travelerCount;

    private Integer childrenCount;

    /**
     * Baseline interests. May be empty — the app learns the rest from feedback.
     */
    private List<TripEnums.Interest> interests;

    private TripEnums.GroupType groupType;

    private TripEnums.AgeRange ageRange;

    private TripEnums.MobilityLimitation mobility;

    private TripEnums.Pace pace;

    private TripEnums.Budget budget;

    private TripEnums.WalkingLevel walking;

    private TripEnums.FoodPreference food;

    /**
     * Dietary restriction (vegetarian, vegan, halal, …). Optional; the app asks
     * for it via the lunch-time pop-up when it is not known yet.
     */
    private TripEnums.Diet diet;
}