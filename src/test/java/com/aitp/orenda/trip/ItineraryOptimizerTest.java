package com.aitp.orenda.trip;
import com.aitp.orenda.model.PoiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItineraryOptimizerTest {

    @Mock
    private TravelTimeEstimator travelTimeEstimator;

    @BeforeEach
    void setUp() {
        lenient().when(travelTimeEstimator.minutesBetween(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(10.0);
        lenient().when(travelTimeEstimator.distanceKm(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(0.8);
    }

    @Test
    void builds_timed_day_plan_with_opening_and_weather_notes() {
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

    @Test
    void open_roof_venue_is_moved_from_rainy_to_clear_day() {
        ItineraryOptimizer optimizer = new ItineraryOptimizer(travelTimeEstimator);

        TripPlanRequest req = balancedRequest();
        PoiResponse museum = poi("11111111-1111-1111-1111-111111111111", "tourism", "museum", Map.of());
        PoiResponse park = poi("22222222-2222-2222-2222-222222222222", "leisure", "park", Map.of());

        TripWeather weather = new TripWeather(List.of(
                new com.aitp.orenda.weather.WeatherResponse.DailyForecast(
                        "2026-08-15", 21, 16, 8.0, 22, 61, "Rain"),
                new com.aitp.orenda.weather.WeatherResponse.DailyForecast(
                        "2026-08-16", 28, 19, 0.0, 8, 0, "Clear sky")));

        List<TripPlanResponse.DayPlan> plan = optimizer.build(
                List.of(scored(park, 70), scored(museum, 80)), req, 2, 41.0082, 28.9784, weather);

        assertThat(plan).hasSize(2);
        // Rainy day 1: only the museum (indoor) is scheduled, never the open-roof park.
        assertThat(plan.get(0).getItems()).extracting(i -> i.getPoi().getSubcategory())
                .containsExactly("museum");
        // Clear day 2: the park finally lands.
        assertThat(plan.get(1).getItems()).extracting(i -> i.getPoi().getSubcategory())
                .contains("park");
    }

    @Test
    void rainy_day_with_only_open_roof_candidates_stays_lighter() {
        ItineraryOptimizer optimizer = new ItineraryOptimizer(travelTimeEstimator);

        TripPlanRequest req = balancedRequest();
        PoiResponse park = poi("22222222-2222-2222-2222-222222222222", "leisure", "park", Map.of());
        TripWeather weather = new TripWeather(List.of(
                new com.aitp.orenda.weather.WeatherResponse.DailyForecast(
                        "2026-08-15", 21, 16, 8.0, 22, 61, "Rain")));

        List<TripPlanResponse.DayPlan> plan = optimizer.build(
                List.of(scored(park, 90)), req, 1, 41.0082, 28.9784, weather);

        assertThat(plan).hasSize(1);
        // Better a free afternoon than sending the traveler out into the rain.
        assertThat(plan.get(0).getItems()).isEmpty();
    }

    @Test
    void walking_budget_skips_too_far_pois_in_foot_mode() {
        when(travelTimeEstimator.minutesBetween(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any()))
                .thenAnswer(inv -> (double) inv.getArgument(2) > 41.03 ? 20.0 : 5.0);
        when(travelTimeEstimator.distanceKm(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenAnswer(inv -> (double) inv.getArgument(2) > 41.03 ? 8.0 : 0.5);

        ItineraryOptimizer optimizer = new ItineraryOptimizer(travelTimeEstimator);

        TripPlanRequest req = balancedRequest();
        req.getBasics().setTransportMode(TripEnums.TransportMode.FOOT);
        PoiResponse near = poi("11111111-1111-1111-1111-111111111111", "culture", "museum", Map.of());
        PoiResponse far = poi("22222222-2222-2222-2222-222222222222", "nature", "park", Map.of());
        far.setLat(41.0500);
        far.setLon(28.9000);

        List<TripPlanResponse.DayPlan> plan = optimizer.build(
                List.of(scored(far, 90), scored(near, 80)), req, 1, 41.0082, 28.9784, TripWeather.empty());

        // MODERATE walk budget = 6 km; the far park is 8 km on foot → skipped.
        assertThat(plan.get(0).getItems())
                .extracting(i -> i.getPoi().getSubcategory())
                .containsExactly("museum");
    }

    @Test
    void travel_budget_skips_an_overly_long_leg() {
        when(travelTimeEstimator.minutesBetween(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any()))
                .thenAnswer(inv -> (double) inv.getArgument(2) > 41.03 ? 150.0 : 10.0);
        when(travelTimeEstimator.distanceKm(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(0.8);

        ItineraryOptimizer optimizer = new ItineraryOptimizer(travelTimeEstimator);

        TripPlanRequest req = balancedRequest();
        req.getBasics().setTransportMode(TripEnums.TransportMode.DRIVING);
        PoiResponse near = poi("11111111-1111-1111-1111-111111111111", "culture", "museum", Map.of());
        PoiResponse far = poi("22222222-2222-2222-2222-222222222222", "historic", "castle", Map.of());
        far.setLat(41.0500);
        far.setLon(28.9000);

        List<TripPlanResponse.DayPlan> plan = optimizer.build(
                List.of(scored(far, 90), scored(near, 80)), req, 1, 41.0082, 28.9784, TripWeather.empty());

        // BALANCED day travel budget = 120 min; a single 150-min leg is dropped.
        assertThat(plan.get(0).getItems())
                .extracting(i -> i.getPoi().getSubcategory())
                .containsExactly("museum");
    }

    @Test
    void recommendations_only_returns_no_day_plan() {
        ItineraryOptimizer optimizer = new ItineraryOptimizer(travelTimeEstimator);

        TripPlanRequest req = balancedRequest();
        req.getStyle().setPlanningStyle(TripEnums.PlanningStyle.RECOMMENDATIONS_ONLY);

        List<TripPlanResponse.DayPlan> plan = optimizer.build(
                List.of(scored(poi("11111111-1111-1111-1111-111111111111", "culture", "museum", Map.of()), 80)),
                req, 2, 41.0082, 28.9784, TripWeather.empty());

        assertThat(plan).isEmpty();
    }

    @Test
    void arrival_time_starts_day_one_after_check_in() {
        ItineraryOptimizer optimizer = new ItineraryOptimizer(travelTimeEstimator);
        TripPlanRequest req = balancedRequest();

        List<TripPlanResponse.DayPlan> plan = optimizer.build(
                List.of(scored(poi("11111111-1111-1111-1111-111111111111", "culture", "museum", Map.of()), 80)),
                req, 1, 41.0082, 28.9784, TripWeather.empty(),
                LocalTime.of(14, 30), null);

        // 14:30 arrival + 10 min travel → first stop at 14:40, ends 16:40 (museum = 120 min).
        assertThat(plan.get(0).getItems()).hasSize(1);
        assertThat(plan.get(0).getItems().get(0).getStartTime()).isEqualTo("14:40");
        assertThat(plan.get(0).getItems().get(0).getEndTime()).isEqualTo("16:40");
        assertThat(plan.get(0).getNotes()).anyMatch(n -> n.contains("Arriving at 14:30"));
    }

    @Test
    void departure_time_trims_stops_that_would_end_after_departure() {
        ItineraryOptimizer optimizer = new ItineraryOptimizer(travelTimeEstimator);
        when(travelTimeEstimator.minutesBetween(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any()))
                .thenAnswer(inv -> (double) inv.getArgument(2) > 41.02 ? 10.0 : 5.0);

        TripPlanRequest req = balancedRequest();
        PoiResponse museum = poi("11111111-1111-1111-1111-111111111111", "culture", "museum", Map.of());
        PoiResponse castle = poi("22222222-2222-2222-2222-222222222222", "historic", "castle", Map.of());
        castle.setLat(41.0201);

        List<TripPlanResponse.DayPlan> plan = optimizer.build(
                List.of(scored(castle, 90), scored(museum, 80)),
                req, 1, 41.0082, 28.9784, TripWeather.empty(),
                null, LocalTime.of(12, 0));

        // Museum: 09:30+5 → 09:35..11:35 (fits before 12:00). Castle: would end 13:35 → dropped.
        assertThat(plan.get(0).getItems()).extracting(i -> i.getPoi().getSubcategory())
                .containsExactly("museum");
        assertThat(plan.get(0).getNotes()).anyMatch(n -> n.contains("Departing at 12:00"));
    }

    @Test
    void children_count_tightens_the_walking_budget_even_without_age_ranges() {
        when(travelTimeEstimator.minutesBetween(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any()))
                .thenAnswer(inv -> (double) inv.getArgument(2) > 41.03 ? 20.0 : 5.0);
        when(travelTimeEstimator.distanceKm(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenAnswer(inv -> (double) inv.getArgument(2) > 41.03 ? 5.0 : 0.5);

        ItineraryOptimizer optimizer = new ItineraryOptimizer(travelTimeEstimator);
        TripPlanRequest req = balancedRequest();
        req.getBasics().setTransportMode(TripEnums.TransportMode.FOOT);
        req.getBasics().setChildrenCount(2);
        req.getProfile().setGroupType(TripEnums.GroupType.FAMILY);

        PoiResponse near = poi("11111111-1111-1111-1111-111111111111", "culture", "museum", Map.of());
        PoiResponse far = poi("22222222-2222-2222-2222-222222222222", "nature", "park", Map.of());
        far.setLat(41.0500);

        List<TripPlanResponse.DayPlan> plan = optimizer.build(
                List.of(scored(far, 90), scored(near, 80)), req, 1, 41.0082, 28.9784, TripWeather.empty());

        // Family MODERATE walk budget = 6 * 0.8 = 4.8 km; the 5 km leg is skipped
        // because childrenCount=2 counts as having children.
        assertThat(plan.get(0).getItems()).extracting(i -> i.getPoi().getSubcategory())
                .containsExactly("museum");
    }

    private TripPlanRequest balancedRequest() {
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
                        .build())
                .style(TripPlanRequest.TravelStyle.builder()
                        .pace(TripEnums.Pace.BALANCED)
                        .walking(TripEnums.WalkingLevel.MODERATE)
                        .budget(TripEnums.Budget.MID_RANGE)
                        .food(TripEnums.FoodPreference.NO_PREFERENCE)
                        .planningStyle(TripEnums.PlanningStyle.DETAILED_SCHEDULE)
                        .build())
                .build();
    }

    private TripPlanResponse.ScoredPoi scored(PoiResponse poi, double score) {
        return TripPlanResponse.ScoredPoi.builder()
                .poi(poi).score(score).factors(Map.of()).reasons(List.of()).build();
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