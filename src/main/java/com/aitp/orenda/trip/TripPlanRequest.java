package com.aitp.orenda.trip;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * Single input object for the trip planner. Mirrors the four-part questionnaire:
 * trip basics, traveler profile, interests, and travel style. Everything the
 * recommendation engine needs to score and rank POIs lives here.
 */
@Data
@Builder
public class TripPlanRequest {

    /* ── 1. Trip basics ───────────────────────────────────────────────────── */

    @Data
    @Builder
    public static class TripBasics {
        @NotBlank(message = "destination must not be blank")
        private String destination;

        @NotNull(message = "startDate must not be null")
        private LocalDate startDate;

        @NotNull(message = "endDate must not be null")
        private LocalDate endDate;

        @NotNull(message = "travelerCount must not be null")
        @Positive(message = "travelerCount must be positive")
        private Integer travelerCount;

        @PositiveOrZero(message = "childrenCount must not be negative")
        private Integer childrenCount;   // may be null / zero

        @NotBlank(message = "accommodationLocation must not be blank")
        private String accommodationLocation;

        private String arrivalTime;      // e.g. "14:30" — free-form, optional
        private String departureTime;    // e.g. "11:00" — free-form, optional

        @NotNull(message = "transportMode must not be null")
        private TripEnums.TransportMode transportMode;
    }

    /* ── 2. Traveler profile ──────────────────────────────────────────────── */

    @Data
    @Builder
    public static class TravelerProfile {
        @NotNull(message = "ageRange must not be null")
        private TripEnums.AgeRange ageRange;

        @NotNull(message = "groupType must not be null")
        private TripEnums.GroupType groupType;

        /**
         * Age bands of any children travelling with the group (family only).
         */
        private List<TripEnums.AgeRange> childAgeRanges;

        private TripEnums.MobilityLimitation mobilityLimitation;
    }

    /* ── 3. Interests ─────────────────────────────────────────────────────── */

    @Data
    @Builder
    public static class Interests {
        @NotNull(message = "selectedInterests must not be null")
        private List<TripEnums.Interest> selectedInterests;

        /**
         * Free-form "anything else you want us to know?" field.
         */
        private String additionalNotes;
    }

    /* ── 4. Travel style ──────────────────────────────────────────────────── */

    @Data
    @Builder
    public static class TravelStyle {
        @NotNull(message = "pace must not be null")
        private TripEnums.Pace pace;

        @NotNull(message = "walking must not be null")
        private TripEnums.WalkingLevel walking;

        @NotNull(message = "budget must not be null")
        private TripEnums.Budget budget;

        @NotNull(message = "food must not be null")
        private TripEnums.FoodPreference food;

        @NotNull(message = "planningStyle must not be null")
        private TripEnums.PlanningStyle planningStyle;
    }

    @Valid
    @NotNull(message = "basics must not be null")
    private TripBasics basics;

    @Valid
    @NotNull(message = "profile must not be null")
    private TravelerProfile profile;

    @Valid
    @NotNull(message = "interests must not be null")
    private Interests interests;

    @Valid
    @NotNull(message = "style must not be null")
    private TravelStyle style;

    /**
     * Anonymous traveler identifier. When present, the planner blends in the
     * traveler's long-term profile and learned preference weights; when absent
     * it plans purely from this request.
     */
    private String sessionId;
}
