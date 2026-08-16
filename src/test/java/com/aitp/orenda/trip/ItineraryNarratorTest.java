package com.aitp.orenda.trip;

import com.aitp.orenda.model.PoiResponse;
import com.aitp.orenda.preference.TripConstraints;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ItineraryNarratorTest {

    private final ItineraryNarrator narrator = new ItineraryNarrator();

    private TripPlanRequest request() {
        return TripPlanRequest.builder()
                .basics(TripPlanRequest.TripBasics.builder()
                        .destination("Istanbul")
                        .startDate(LocalDate.of(2026, 8, 15))
                        .endDate(LocalDate.of(2026, 8, 16))
                        .travelerCount(2)
                        .accommodationLocation("41.0082,28.9784")
                        .transportMode(TripEnums.TransportMode.FOOT)
                        .build())
                .profile(TripPlanRequest.TravelerProfile.builder()
                        .ageRange(TripEnums.AgeRange.AGE_25_34)
                        .groupType(TripEnums.GroupType.COUPLE)
                        .mobilityLimitation(TripEnums.MobilityLimitation.NONE)
                        .build())
                .interests(TripPlanRequest.Interests.builder()
                        .selectedInterests(List.of(TripEnums.Interest.MUSEUMS))
                        .additionalNotes("prefer mornings")
                        .build())
                .style(TripPlanRequest.TravelStyle.builder()
                        .pace(TripEnums.Pace.BALANCED)
                        .walking(TripEnums.WalkingLevel.MODERATE)
                        .budget(TripEnums.Budget.MID_RANGE)
                        .food(TripEnums.FoodPreference.LOCAL)
                        .planningStyle(TripEnums.PlanningStyle.DETAILED_SCHEDULE)
                        .build())
                .build();
    }

    private TripPlanResponse.ScoredPoi museum() {
        return TripPlanResponse.ScoredPoi.builder()
                .poi(PoiResponse.builder()
                        .id("11111111-1111-1111-1111-111111111111")
                        .nameTr("Topkapı Palace")
                        .category("historic")
                        .subcategory("palace")
                        .lat(41.0117)
                        .lon(28.9833)
                        .build())
                .score(80.0)
                .factors(Map.of())
                .reasons(List.of("Matches your interest in museums"))
                .travelMinutes(12)
                .visitMinutes(120)
                .startTime("09:40")
                .endTime("11:40")
                .openAtScheduledTime(true)
                .build();
    }

    @Test
    void overall_narrative_mentions_trip_shape_and_insight() {
        ItineraryNarrator.NarrativeOutput out = narrator.narrate(
                request(), List.of(), List.of(museum()),
                "I noticed you tend to prefer cultural experiences.", "Aug 15: Slight rain, 20°C",
                TripConstraints.NONE, TripEnums.Budget.MID_RANGE, 15.0);

        assertThat(out.overall())
                .contains("Istanbul")
                .contains("couple")
                .contains("balanced")
                .contains("cultural experiences")
                .contains("\"prefer mornings\" in mind")
                .contains("top 1 picks");
    }

    @Test
    void day_narrative_walks_through_the_schedule() {
        TripPlanResponse.DayPlan day = TripPlanResponse.DayPlan.builder()
                .day(1)
                .date("2026-08-15")
                .weather("Slight rain, 20°C")
                .items(List.of(museum()))
                .notes(List.of("Lunch break recommended around 13:00."))
                .build();

        String narrative = narrator.narrate(
                request(), List.of(day), List.of(museum()), null, null,
                TripConstraints.NONE, TripEnums.Budget.MID_RANGE, 15.0).dayNarratives().get(0);

        assertThat(narrative)
                .contains("Day 1")
                .contains("Saturday, Aug 15")
                .containsIgnoringCase("leans indoor")
                .contains("Topkapı Palace")
                .contains("09:40")
                .contains("12 min from your previous stop")
                .contains("Matches your interest in museums")
                .contains("Lunch break");
    }

    @Test
    void no_day_narratives_when_plan_is_empty() {
        ItineraryNarrator.NarrativeOutput out = narrator.narrate(
                request(), List.of(), List.of(), null, null,
                TripConstraints.NONE, TripEnums.Budget.MID_RANGE, 15.0);

        assertThat(out.dayNarratives()).isEmpty();
        assertThat(out.overall()).contains("No suggestions matched yet");
    }

    @Test
    void overall_narrative_explains_feedback_constraints() {
        TripConstraints constraints = new TripConstraints(
                TripEnums.Budget.MID_RANGE, 8.0, true, true, true);

        ItineraryNarrator.NarrativeOutput out = narrator.narrate(
                request(), List.of(), List.of(museum()), null, null,
                constraints, TripEnums.Budget.MID_RANGE, 8.0);

        assertThat(out.overall())
                .contains("too expensive")
                .contains("within 8.0 km of your base")
                .contains("crowded, popular venues")
                .contains("adult-oriented venues are left out")
                .contains("quieter spots are favored");
    }
}