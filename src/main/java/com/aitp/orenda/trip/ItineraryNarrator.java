package com.aitp.orenda.trip;

import com.aitp.orenda.model.PoiResponse;
import com.aitp.orenda.preference.TripConstraints;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns the structured, optimized itinerary into a natural-language narrative —
 * the "AI-generated itinerary" stage of the pipeline. It explains the reasoning
 * behind each recommendation (learned preferences, weather fit, opening hours,
 * travel logistics, feedback constraints) rather than just listing places.
 * <p>
 * Template-based and deterministic: the backend supplies reliable structured
 * data, and this layer narrates it. It can later be swapped for (or augmented
 * with) an LLM without changing the pipeline.
 */
@Component
public class ItineraryNarrator {

    /**
     * A generated narrative: {@code overall} is the trip-level story, and
     * {@code dayNarratives} lines up with the day-plan entries.
     */
    public record NarrativeOutput(String overall, List<String> dayNarratives) {
    }

    public NarrativeOutput narrate(TripPlanRequest req, List<TripPlanResponse.DayPlan> dayPlan,
                                   List<TripPlanResponse.ScoredPoi> suggestions,
                                   String preferenceInsight, String weatherSummary,
                                   TripConstraints constraints, TripEnums.Budget effectiveBudget,
                                   double effectiveRadiusKm) {
        List<String> dayNarratives = new ArrayList<>();
        for (TripPlanResponse.DayPlan day : dayPlan) {
            dayNarratives.add(narrateDay(day, constraints));
        }
        String overall = narrateOverall(req, dayPlan, suggestions, preferenceInsight, weatherSummary,
                constraints, effectiveBudget, effectiveRadiusKm);
        return new NarrativeOutput(overall, dayNarratives);
    }

    private String narrateOverall(TripPlanRequest req, List<TripPlanResponse.DayPlan> dayPlan,
                                  List<TripPlanResponse.ScoredPoi> suggestions,
                                  String preferenceInsight, String weatherSummary,
                                  TripConstraints constraints, TripEnums.Budget effectiveBudget,
                                  double effectiveRadiusKm) {
        StringBuilder sb = new StringBuilder();
        String destination = req.getBasics().getDestination();
        String group = req.getProfile().getGroupType().name().toLowerCase().replace('_', ' ');
        String pace = req.getStyle().getPace().name().toLowerCase();
        String walking = req.getStyle().getWalking().name().toLowerCase().replace('_', ' ');
        String budget = req.getStyle().getBudget().name().toLowerCase().replace('_', ' ');

        sb.append("I've planned a ").append(group).append(" trip to ").append(destination)
                .append(" at a ").append(pace).append(" pace, with ").append(walking)
                .append(" walking and a ").append(budget).append(" budget.");

        if (preferenceInsight != null && !preferenceInsight.isBlank()) {
            sb.append(' ').append(preferenceInsight);
        }

        String notes = req.getInterests().getAdditionalNotes();
        if (notes != null && !notes.isBlank()) {
            sb.append(" I've kept \"").append(notes.trim()).append("\" in mind.");
        }

        if (weatherSummary != null && !weatherSummary.isBlank()) {
            sb.append(" Weather outlook: ").append(weatherSummary).append('.');
        }

        sb.append(constraintExplanations(constraints, effectiveBudget, effectiveRadiusKm));

        if (dayPlan.isEmpty()) {
            int top = Math.min(suggestions.size(), 5);
            sb.append(top == 0 ? " No suggestions matched yet — try widening your interests or area."
                    : " Here are your top " + top + " picks to start with.");
        } else {
            sb.append(" Here's the day-by-day plan.");
        }
        return sb.toString();
    }

    /**
     * Prose explaining each adjustment the planner made because of feedback
     * reasons ("too expensive", "too far", "too crowded", "not suitable for
     * kids", "prefer quieter").
     */
    private String constraintExplanations(TripConstraints constraints, TripEnums.Budget effectiveBudget,
                                          double effectiveRadiusKm) {
        if (constraints == null || TripConstraints.NONE.equals(constraints)) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (constraints.budgetCap() != null) {
            parts.add("I've kept the budget at " + budgetLabel(effectiveBudget)
                    + " because you flagged some picks as too expensive");
        }
        if (constraints.maxRadiusKm() != null) {
            parts.add("everything stays within " + Math.round(effectiveRadiusKm * 10.0) / 10.0
                    + " km of your base since you said some stops were too far");
        }
        if (constraints.avoidCrowded()) {
            parts.add("I've de-prioritized crowded, popular venues");
        }
        if (constraints.familySafe()) {
            parts.add("adult-oriented venues are left out");
        }
        if (constraints.quiet()) {
            parts.add("quieter spots are favored over lively ones");
        }
        if (parts.isEmpty()) {
            return "";
        }
        return " A few adjustments based on your feedback: " + humanJoin(parts) + ".";
    }

    private String narrateDay(TripPlanResponse.DayPlan day, TripConstraints constraints) {
        StringBuilder sb = new StringBuilder();
        sb.append("Day ").append(day.getDay()).append(" · ").append(formatDate(day.getDate()));

        String weather = day.getWeather();
        if (weather != null && !weather.isBlank()) {
            boolean rainy = weather.toLowerCase().contains("rain");
            sb.append(" — ").append(weather).append(rainy ? ", so today leans indoor."
                    : ", a good day to be out.");
        }
        sb.append(System.lineSeparator());

        if (day.getItems() == null || day.getItems().isEmpty()) {
            sb.append("A lighter day — no stops scheduled.");
        } else {
            for (TripPlanResponse.ScoredPoi item : day.getItems()) {
                sb.append("- ").append(name(item.getPoi())).append(" at ").append(item.getStartTime());
                if (item.getTravelMinutes() != null) {
                    sb.append(" (about ").append(item.getTravelMinutes())
                            .append(" min from your previous stop)");
                }
                if (item.getEndTime() != null) {
                    sb.append(", until ").append(item.getEndTime());
                }
                if (item.getVisitMinutes() != null) {
                    sb.append(" — allow around ").append(item.getVisitMinutes()).append(" minutes");
                }
                sb.append('.');
                if (Boolean.FALSE.equals(item.getOpenAtScheduledTime())) {
                    sb.append(" Note: it may open later in the day, so the timing can flex.");
                }
                if (item.getReasons() != null && !item.getReasons().isEmpty()) {
                    sb.append(" Why: ").append(item.getReasons().get(0)).append('.');
                }
                sb.append(System.lineSeparator());
            }
        }

        boolean calmDay = day.getItems() != null && !day.getItems().isEmpty()
                && constraints != null && constraints.quiet()
                && day.getItems().stream().noneMatch(i -> isLively(i.getPoi()));
        if (calmDay) {
            sb.append("Kept calm today — no lively venues, per your preference for quieter places.")
                    .append(System.lineSeparator());
        }

        if (day.getNotes() != null && !day.getNotes().isEmpty()) {
            sb.append("Tip: ").append(String.join(" ", day.getNotes())).append(System.lineSeparator());
        }
        return sb.toString().stripTrailing();
    }

    private static boolean isLively(PoiResponse poi) {
        if (poi == null || poi.getCategory() == null) {
            return false;
        }
        String cat = poi.getCategory();
        String sub = poi.getSubcategory() == null ? "" : poi.getSubcategory();
        return "entertainment".equals(cat)
                || List.of("nightclub", "casino", "bar", "pub", "theme_park", "water_park", "stadium").contains(sub);
    }

    private static String budgetLabel(TripEnums.Budget budget) {
        return budget == null ? "a comfortable level" : budget.name().toLowerCase().replace('_', ' ');
    }

    private static String formatDate(String isoDate) {
        try {
            LocalDate date = LocalDate.parse(isoDate);
            return date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                    + ", " + date.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                    + " " + date.getDayOfMonth();
        } catch (Exception e) {
            return isoDate;
        }
    }

    private static String name(PoiResponse poi) {
        if (poi == null) {
            return "a stop";
        }
        if (poi.getNameTr() != null && !poi.getNameTr().isBlank()) {
            return poi.getNameTr();
        }
        if (poi.getNameEn() != null && !poi.getNameEn().isBlank()) {
            return poi.getNameEn();
        }
        return poi.getCategory() == null ? "a stop" : "a " + poi.getCategory() + " spot";
    }

    private static String humanJoin(List<String> items) {
        if (items.size() <= 1) {
            return items.get(0);
        }
        if (items.size() == 2) {
            return items.get(0) + " and " + items.get(1);
        }
        return String.join(", ", items.subList(0, items.size() - 1))
                + ", and " + items.get(items.size() - 1);
    }
}