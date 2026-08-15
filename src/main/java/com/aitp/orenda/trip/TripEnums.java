package com.aitp.orenda.trip;

/**
 * Enumerations that power the trip-planning questionnaire. Kept in one file to
 * make the option sets easy to discover and reuse across request/response DTOs.
 */
public final class TripEnums {

    private TripEnums() {
    }

    /**
     * How the group gets around while exploring.
     */
    public enum TransportMode {
        DRIVING, FOOT, BIKE, TRANSIT, TAXI
    }

    /**
     * Age band — intentionally coarse; exact ages are not needed to plan.
     */
    public enum AgeRange {
        UNDER_18, AGE_18_24, AGE_25_34, AGE_35_44, AGE_45_54, AGE_55_64, AGE_65_PLUS, MIXED
    }

    /**
     * Who is travelling together.
     */
    public enum GroupType {
        SOLO, COUPLE, FAMILY, FRIENDS
    }

    /**
     * Optional self-reported mobility constraint (voluntary).
     */
    public enum MobilityLimitation {
        NONE, LIMITED_WALKING, WHEELCHAIR, STROLLER
    }

    /**
     * Selectable interest preferences.
     */
    public enum Interest {
        HISTORY, MUSEUMS, NATURE, BEACHES, FOOD, SHOPPING, NIGHTLIFE,
        PHOTOGRAPHY, ARCHITECTURE, ADVENTURE, LOCAL_CULTURE, LUXURY,
        HIDDEN_GEMS, FAMILY_ACTIVITIES
    }

    /**
     * How full each day should be.
     */
    public enum Pace {
        RELAXED, BALANCED, PACKED
    }

    /**
     * How much walking the group is willing to do.
     */
    public enum WalkingLevel {
        MINIMAL, MODERATE, LOTS
    }

    /**
     * Spending comfort band.
     */
    public enum Budget {
        BUDGET, MID_RANGE, PREMIUM, LUXURY
    }

    /**
     * Food leaning.
     */
    public enum FoodPreference {
        LOCAL, FINE_DINING, STREET_FOOD, VEGETARIAN, VEGAN, NO_PREFERENCE
    }

    /**
     * How much hand-holding the traveller wants.
     */
    public enum PlanningStyle {
        DETAILED_SCHEDULE, RECOMMENDATIONS_ONLY, SURPRISE_ME
    }
}
