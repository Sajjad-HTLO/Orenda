package com.aitp.orenda.trip;

import com.aitp.orenda.model.PoiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItineraryOptimizerTest {

    @Mock
    private TravelTimeEstimator travelTimeEstimator;

    @Test
    void builds_timed_day_plan_with_opening_and_weather_notes() {
        when(travelTimeEstimator.minutesBetween(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(10.0);
        when(travelTimeEstimator.distanceKm(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(0.8);

        ItineraryOptimizer optimizer = new ItineraryOptimizer(travelTimeEstimator);

        TripPlanRequest req = TripPlanRequest.builder()
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
                        .build())
                .style(TripPlanRequest.TravelStyle.builder()
                        .pace(TripEnums.Pace.BALANCED)
                        .walking(TripEnums.WalkingLevel.MODERATE)
                        .budget(TripEnums.Budget.MID_RANGE)
                        .food(TripEnums.FoodPreference.NO_PREFERENCE)
                        .planningStyle(TripEnums.PlanningStyle.DETAILED_SCHEDULE)
                        .build())
                .build();

        PoiResponse museum = poi("11111111-1111-1111-1111-111111111111", "tourism", "museum",
                Map.of("opening_hours", "09:00-19:00"));
        PoiResponse park = poi("22222222-2222-2222-2222-222222222222", "leisure", "park",
                Map.of());

        TripWeather weather = new TripWeather(List.of(
                new com.aitp.orenda.weather.WeatherResponse.DailyForecast(
                        "2026-08-15", 20, 14, 6.0, 15, 61, "Slight rain"),
                new com.aitp.orenda.weather.WeatherResponse.DailyForecast(
                        "2026-08-16", 26, 17, 0.0, 8, 0, "Clear sky")));

        TripPlanResponse.ScoredPoi museumScored = TripPlanResponse.ScoredPoi.builder()
                .poi(museum).score(80).factors(Map.of()).reasons(List.of()).build();
        TripPlanResponse.ScoredPoi parkScored = TripPlanResponse.ScoredPoi.builder()
                .poi(park).score(60).factors(Map.of()).reasons(List.of()).build();

        List<TripPlanResponse.DayPlan> plan = optimizer.build(
                List.of(museumScored, parkScored), req, 2, 41.0082, 28.9784, weather);

        assertThat(plan).hasSize(2);
        TripPlanResponse.DayPlan rainyDay = plan.get(0);
        // Rain on day 1 → museum (indoor) is scheduled ahead of the park
        assertThat(rainyDay.getItems().get(0).getPoi().getSubcategory()).isEqualTo("museum");
        assertThat(rainyDay.getWeather()).containsIgnoringCase("rain");
        assertThat(rainyDay.getItems().get(0).getStartTime()).isEqualTo("09:40");
        assertThat(rainyDay.getItems().get(0).getTravelMinutes()).isEqualTo(10);
        assertThat(rainyDay.getItems().get(0).getVisitMinutes()).isEqualTo(120);
        assertThat(rainyDay.getItems().get(0).getOpenAtScheduledTime()).isTrue();
        assertThat(rainyDay.getNotes()).anyMatch(n -> n.contains("indoor"));

        TripPlanResponse.DayPlan clearDay = plan.get(1);
        assertThat(clearDay.getWeather()).contains("Clear");
    }

    private PoiResponse poi(String id, String category, String subcategory, Map<String, Object> attrs) {
        return PoiResponse.builder()
                .id(id)
                .nameTr("POI " + id.substring(0, 8))
                .category(category)
                .subcategory(subcategory)
                .lat(41.01)
                .lon(28.98)
                .completenessScore(80)
                .distanceKm(1.0)
                .attributes(attrs)
                .build();
    }
}