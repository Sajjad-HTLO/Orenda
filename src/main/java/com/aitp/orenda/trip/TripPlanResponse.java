package com.aitp.orenda.trip;

import com.aitp.orenda.model.PoiResponse;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Result of a trip-planning request: a ranked, scored list of suggested POIs plus
 * an optional day-by-day plan. The ranked POIs are always returned so a client can
 * render them without consuming the (optional) AI-generated plan.
 */
@Data
@Builder
public class TripPlanResponse {

    /**
     * Number of full days derived from start/end dates.
     */
    private int tripDays;

    /**
     * Human-readable rationale for the highlighted selections.
     */
    private String summary;

    /**
     * Suggested POIs, best-first.
     */
    private List<ScoredPoi> suggestions;

    /**
     * Optional day-by-day grouping (empty when planningStyle = RECOMMENDATIONS_ONLY).
     */
    private List<DayPlan> dayPlan;

    /**
     * Reasons for any automatic adjustments (e.g. expanded search radius, capped pace).
     */
    private List<String> notes;

    @Data
    @Builder
    public static class ScoredPoi {
        private PoiResponse poi;
        private double score;                // 0–100, higher is better
        private Map<String, Double> factors; // per-dimension contribution breakdown
        private List<String> reasons;        // why this POI suits this trip
    }

    @Data
    @Builder
    public static class DayPlan {
        private int day;
        private String date;
        private List<ScoredPoi> items;
    }
}
