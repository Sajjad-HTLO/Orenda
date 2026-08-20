package com.aitp.orenda.preference;

import com.aitp.orenda.trip.TripEnums;

/**
 * Trip-shaping constraints learned from feedback reasons. Unlike preference
 * weights (which bias scoring), these restrict the plan itself.
 *
 * @param budgetCap    effective budget ceiling (from "too expensive")
 * @param maxRadiusKm  cap on the search radius around the base (from "too far")
 * @param avoidCrowded de-prioritize popular venues (from "too crowded")
 * @param familySafe   exclude adult-oriented venues (from "not suitable for kids")
 * @param quiet        favor quiet spots, de-rank nightlife (from "prefer quieter")
 */
public record TripConstraints(
        TripEnums.Budget budgetCap,
        Double maxRadiusKm,
        boolean avoidCrowded,
        boolean familySafe,
        boolean quiet
) {
    public static final TripConstraints NONE = new TripConstraints(null, null, false, false, false);
}