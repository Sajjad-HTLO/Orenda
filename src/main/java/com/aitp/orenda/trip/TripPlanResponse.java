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

    /**
     * Trip-wide weather overview, e.g. "Aug 15: Sunny 28°C · Aug 16: Rain 19°C".
     * Empty when the forecast is unavailable.
     */
    private String weatherSummary;

    /**
     * Learned-preference insight, e.g. "I noticed you tend to prefer cultural
     * experiences and local food. I've adjusted your recommendations
     * accordingly." Null when no learned preferences apply.
     */
    private String preferenceInsight;

    /**
     * Natural-language trip narrative produced by the AI-itinerary stage,
     * e.g. "I've planned a couple trip to Istanbul at a balanced pace…".
     */
    private String narrative;

    @Data
    @Builder
    public static class ScoredPoi {
        private PoiResponse poi;
        private double score;                // 0–100, higher is better
        private Map<String, Double> factors; // per-dimension contribution breakdown
        private List<String> reasons;        // why this POI suits this trip

        /* ── Scheduling (populated inside a day plan) ── */
        private Integer travelMinutes;       // from previous stop / base to this POI
        private Integer visitMinutes;        // estimated dwell time
        private String startTime;            // e.g. "10:00"
        private String endTime;              // e.g. "11:30"
        private Boolean openAtScheduledTime; // null when opening hours are unknown
    }

    @Data
    @Builder
    public static class DayPlan {
        private int day;
        private String date;
        private String weather;              // forecast text for this day
        private List<ScoredPoi> items;
        private List<String> notes;          // day-specific advice (lunch, rain, walking)
        private String narrative;            // natural-language narration of this day
    }
}
