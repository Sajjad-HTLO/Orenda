package com.aitp.orenda.trip;

import com.aitp.orenda.model.PoiResponse;
import com.aitp.orenda.preference.FeedbackReason;
import com.aitp.orenda.preference.PreferenceCategory;
import com.aitp.orenda.preference.PreferenceFeedbackRequest;
import com.aitp.orenda.preference.PreferenceReaction;
import com.aitp.orenda.preference.PreferenceRepository;
import com.aitp.orenda.preference.PreferenceService;
import com.aitp.orenda.preference.TravelerProfileRequest;
import com.aitp.orenda.preference.TravelerProfileResponse;
import com.aitp.orenda.repository.PoiRepository;
import com.aitp.orenda.weather.WeatherResponse;
import com.aitp.orenda.weather.WeatherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end "happy day" journey across the real (non-mocked) pipeline services.
 * <p>
 * Steps covered, in order:
 * 1. onboarding profile (incl. dietary restriction)
 * 2. personalized trip plan (ranked suggestions + timed day plan + lunch block)
 * 3. preference feedback (love / dislike-with-reason / kids-safe) — the write path
 * 4. re-plan with the learned weights + constraints — the read path
 * 5. weather-driven scheduling (open-roof venues never land on a rainy day)
 * <p>
 * Only the repositories, weather provider and travel-time estimator are mocked —
 * every scoring / optimization / narration / lunch decision runs the real code.
 */
@ExtendWith(MockitoExtension.class)
class TravelerJourneyTest {

    private static final String SESSION = "selin-2026";

    @Mock
    private PoiRepository poiRepository;

    @Mock
    private WeatherService weatherService;

    @Mock
    private TravelTimeEstimator travelTimeEstimator;

    @Mock
    private PreferenceRepository preferenceRepository;

    private PreferenceService preferenceService;
    private TripRecommendationService planner;

    @BeforeEach
    void setUp() {
        lenient().when(travelTimeEstimator.minutesBetween(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(10.0);
        lenient().when(travelTimeEstimator.distanceKm(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(0.8);
        lenient().when(preferenceRepository.loadWeights(anyString())).thenReturn(Map.of());
        lenient().when(preferenceRepository.loadConstraints(anyString())).thenReturn(Map.of());
        lenient().when(preferenceRepository.loadProfileInterests(anyString())).thenReturn(List.of());
        // processFeedback reads a weight for every preference category.
        lenient().when(preferenceRepository.loadWeight(eq(SESSION), anyString())).thenReturn(0.5);

        preferenceService = new PreferenceService(preferenceRepository, poiRepository);
        planner = new TripRecommendationService(
                poiRepository, weatherService, travelTimeEstimator,
                new ItineraryOptimizer(travelTimeEstimator),
                preferenceService, new ItineraryNarrator(),
                new LunchPlanner(poiRepository, travelTimeEstimator));
    }

    // ── Journey 1: onboarding → personalized plan with diet-filtered lunch ──

    @Test
    void journey_onboard_then_plan_with_diet_filtered_lunch() {
        // Step 1 — create the profile (the repository stores it; we return it on read)
        when(preferenceRepository.findProfile(SESSION)).thenReturn(
                TravelerProfileResponse.builder()
                        .sessionId(SESSION)
                        .interests(List.of(TripEnums.Interest.HISTORY, TripEnums.Interest.MUSEUMS))
                        .groupType(TripEnums.GroupType.COUPLE)
                        .diet(TripEnums.Diet.VEGETARIAN)
                        .food(TripEnums.FoodPreference.LOCAL)
                        .build());
        TravelerProfileResponse profile = preferenceService.upsertProfile(
                TravelerProfileRequest.builder()
                        .sessionId(SESSION)
                        .interests(List.of(TripEnums.Interest.HISTORY, TripEnums.Interest.MUSEUMS))
                        .groupType(TripEnums.GroupType.COUPLE)
                        .diet(TripEnums.Diet.VEGETARIAN)
                        .food(TripEnums.FoodPreference.LOCAL)
                        .build());
        assertThat(profile.getDiet()).isEqualTo(TripEnums.Diet.VEGETARIAN);

        // Data the planner will read
        PoiResponse museum = poi("11111111-1111-1111-1111-111111111111", "historic", "palace",
                "Topkapı Sarayı", Map.of("opening_hours", "09:00-19:00", "wikidata", "Q201297"));
        PoiResponse park = poi("22222222-2222-2222-2222-222222222222", "leisure", "park",
                "Gülhane Parkı", Map.of());
        PoiResponse vegRest = poi("33333333-3333-3333-3333-333333333333", "food_drink", "restaurant",
                "Zencefil", Map.of("diet:vegetarian", "yes"));
        PoiResponse kebab = poi("44444444-4444-4444-4444-444444444444", "food_drink", "restaurant",
                "Kebab House", Map.of("cuisine", "kebab"));

        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(museum, park));
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), eq("food_drink"), anyInt(), anyInt()))
                .thenReturn(List.of(kebab, vegRest));
        when(weatherService.getWeather(anyDouble(), anyDouble(), anyInt())).thenReturn(sunny(2, "2026-08-15"));

        // Step 2 — plan the trip with the stored session
        TripPlanResponse plan = planner.recommend(tripRequest(2, "2026-08-15"));

        // Ranked suggestions, best first
        assertThat(plan.getSuggestions()).isNotEmpty();
        assertThat(plan.getSuggestions().get(0).getPoi().getId())
                .isEqualTo("11111111-1111-1111-1111-111111111111");

        // A real, timed day-by-day plan
        assertThat(plan.getDayPlan()).hasSize(2);
        TripPlanResponse.DayPlan day1 = plan.getDayPlan().get(0);
        assertThat(day1.getItems()).isNotEmpty();
        assertThat(day1.getItems().get(0).getStartTime()).isNotNull();
        assertThat(day1.getItems().get(0).getVisitMinutes()).isPositive();

        // The diet is known → no pop-up needed, and the vegetarian spot is ranked first
        TripPlanResponse.LunchSlot lunch = day1.getLunch();
        assertThat(lunch).isNotNull();
        assertThat(lunch.getNeedsDietInfo()).isFalse();
        assertThat(lunch.getNearbyRestaurants())
                .extracting(r -> r.getPoi().getId())
                .containsExactly("33333333-3333-3333-3333-333333333333",
                        "44444444-4444-4444-4444-444444444444");
        assertThat(lunch.getNearbyRestaurants().get(0).getReasons())
                .anyMatch(r -> r.contains("vegetarian"));
        assertThat(lunch.getReturnToHotel().getTravelMinutes()).isEqualTo(10);

        // The AI layer explains the plan
        assertThat(plan.getNarrative()).isNotBlank();
        assertThat(plan.getDayPlan().get(0).getNarrative()).isNotBlank();
    }

    // ── Journey 2: feedback writes → re-plan reads the learned state ────────

    @Test
    void journey_feedback_loop_shapes_the_next_plan() {
        PoiResponse museum = poi("11111111-1111-1111-1111-111111111111", "historic", "palace",
                "Topkapı Sarayı", Map.of("opening_hours", "09:00-19:00"));
        PoiResponse nightclub = poi("22222222-2222-2222-2222-222222222222", "entertainment", "nightclub",
                "Reina", Map.of());

        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(museum, nightclub));
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), eq("food_drink"), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(weatherService.getWeather(anyDouble(), anyDouble(), anyInt())).thenReturn(sunny(1, "2026-08-15"));
        when(poiRepository.findById("11111111-1111-1111-1111-111111111111")).thenReturn(java.util.Optional.of(museum));
        when(poiRepository.findById("22222222-2222-2222-2222-222222222222")).thenReturn(java.util.Optional.of(nightclub));

        // First plan
        TripPlanResponse first = planner.recommend(tripRequest(1, "2026-08-15"));
        assertThat(first.getSuggestions()).isNotEmpty();

        // 1) LOVE on the museum → CULTURE weight rises (0.5 + 0.2*(1.0-0.5) = 0.6)
        preferenceService.processFeedback(feedback(museum.getId(), PreferenceReaction.LOVE, null));
        ArgumentCaptor<Double> weight = ArgumentCaptor.forClass(Double.class);
        verify(preferenceRepository).recordFeedback(eq(SESSION), eq(museum.getId()), eq(PreferenceReaction.LOVE),
                isNull(), isNull(), eq(PreferenceCategory.CULTURE.name()), weight.capture());
        assertThat(weight.getValue()).isEqualTo(0.6);

        // 2) "too expensive" → budget cap constraint (starts at PREMIUM)
        preferenceService.processFeedback(feedback(museum.getId(), PreferenceReaction.DISLIKE, FeedbackReason.TOO_EXPENSIVE));
        verify(preferenceRepository).upsertConstraint(SESSION, "BUDGET_CAP", "PREMIUM");

        // 3) nightclub is "not suitable for kids" → family-safe constraint
        preferenceService.processFeedback(feedback(nightclub.getId(), PreferenceReaction.NOT_INTERESTED,
                FeedbackReason.NOT_SUITABLE_FOR_KIDS));
        verify(preferenceRepository).upsertConstraint(SESSION, "FAMILY_SAFE", "true");

        // Re-plan reads the learned state (as persisted by the writes above)
        when(preferenceRepository.loadWeights(SESSION))
                .thenReturn(Map.of(PreferenceCategory.CULTURE.name(), 0.9, PreferenceCategory.NIGHTLIFE.name(), 0.1));
        when(preferenceRepository.loadConstraints(SESSION))
                .thenReturn(Map.of("BUDGET_CAP", "MID_RANGE", "FAMILY_SAFE", "true"));

        TripPlanResponse next = planner.recommend(tripRequest(1, "2026-08-15"));

        // Adult venue gone (family-safe), culture boosted, constraints explained
        assertThat(next.getSuggestions())
                .noneMatch(s -> nightclub.getId().equals(s.getPoi().getId()));
        assertThat(next.getPreferenceInsight()).contains("cultural experiences");
        assertThat(next.getNotes())
                .anyMatch(n -> n.contains("Adult-oriented venues excluded"))
                .anyMatch(n -> n.contains("Budget capped at mid range"));
    }

    // ── Journey 3: rainy forecast keeps open-roof venues off the rainy day ──

    @Test
    void journey_rainy_day_never_schedules_open_roof_venues() {
        PoiResponse museum = poi("11111111-1111-1111-1111-111111111111", "historic", "palace",
                "Topkapı Sarayı", Map.of("opening_hours", "09:00-19:00"));
        PoiResponse park = poi("22222222-2222-2222-2222-222222222222", "leisure", "park",
                "Gülhane Parkı", Map.of());

        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(museum, park));
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), eq("food_drink"), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(weatherService.getWeather(anyDouble(), anyDouble(), anyInt()))
                .thenReturn(mixedWeather());

        TripPlanResponse plan = planner.recommend(tripRequest(3, "2026-08-15"));

        // Scoring keeps the park (only 1 of 3 days rainy) so it can still be used.
        assertThat(plan.getSuggestions())
                .anyMatch(s -> park.getId().equals(s.getPoi().getId()));

        // But it is never scheduled on the rainy day.
        TripPlanResponse.DayPlan rainyDay = plan.getDayPlan().get(0);
        assertThat(rainyDay.getItems())
                .extracting(i -> i.getPoi().getSubcategory())
                .noneMatch("park"::equals);
        assertThat(rainyDay.getItems())
                .extracting(i -> i.getPoi().getSubcategory())
                .contains("palace");

        // And it lands on a clear day instead.
        boolean scheduledOnClearDay = plan.getDayPlan().stream().skip(1)
                .flatMap(d -> d.getItems().stream())
                .anyMatch(i -> park.getId().equals(i.getPoi().getId()));
        assertThat(scheduledOnClearDay).isTrue();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private TripPlanRequest tripRequest(int days, String start) {
        return TripPlanRequest.builder()
                .basics(TripPlanRequest.TripBasics.builder()
                        .destination("Istanbul")
                        .startDate(LocalDate.parse(start))
                        .endDate(LocalDate.parse(start).plusDays(days - 1L))
                        .travelerCount(2)
                        .accommodationLocation("41.0082,28.9784")
                        .transportMode(TripEnums.TransportMode.FOOT)
                        .build())
                .profile(TripPlanRequest.TravelerProfile.builder()
                        .ageRange(TripEnums.AgeRange.AGE_25_34)
                        .groupType(TripEnums.GroupType.COUPLE)
                        .mobilityLimitation(TripEnums.MobilityLimitation.NONE)
                        .diet(TripEnums.Diet.VEGETARIAN)
                        .build())
                .interests(TripPlanRequest.Interests.builder()
                        .selectedInterests(List.of(TripEnums.Interest.HISTORY, TripEnums.Interest.MUSEUMS))
                        .build())
                .style(TripPlanRequest.TravelStyle.builder()
                        .pace(TripEnums.Pace.BALANCED)
                        .walking(TripEnums.WalkingLevel.MODERATE)
                        .budget(TripEnums.Budget.MID_RANGE)
                        .food(TripEnums.FoodPreference.LOCAL)
                        .planningStyle(TripEnums.PlanningStyle.DETAILED_SCHEDULE)
                        .build())
                .sessionId(SESSION)
                .build();
    }

    private PreferenceFeedbackRequest feedback(String poiId, PreferenceReaction reaction,
                                               FeedbackReason reason) {
        return PreferenceFeedbackRequest.builder()
                .poiId(poiId)
                .sessionId(SESSION)
                .reaction(reaction)
                .reason(reason)
                .build();
    }

    private PoiResponse poi(String id, String category, String subcategory, String name,
                            Map<String, Object> attrs) {
        return PoiResponse.builder()
                .id(id)
                .nameTr(name)
                .category(category)
                .subcategory(subcategory)
                .lat(41.0117)
                .lon(28.9833)
                .completenessScore(80)
                .distanceKm(1.0)
                .attributes(attrs)
                .build();
    }

    private WeatherResponse sunny(int days, String start) {
        var loc = new WeatherResponse.Location(41.0082, 28.9784, "Europe/Istanbul");
        var current = new WeatherResponse.CurrentConditions(29, 30, 50, 10, 180, 0, "Clear sky");
        List<WeatherResponse.DailyForecast> daily = new java.util.ArrayList<>();
        for (int i = 0; i < days; i++) {
            daily.add(new WeatherResponse.DailyForecast(
                    LocalDate.parse(start).plusDays(i).toString(), 30, 21, 0.0, 10, 0, "Clear sky"));
        }
        return new WeatherResponse(loc, current, daily);
    }

    private WeatherResponse mixedWeather() {
        var loc = new WeatherResponse.Location(41.0082, 28.9784, "Europe/Istanbul");
        var current = new WeatherResponse.CurrentConditions(21, 22, 85, 15, 200, 61, "Rain");
        return new WeatherResponse(loc, current, List.of(
                new WeatherResponse.DailyForecast("2026-08-15", 22, 17, 8.0, 22, 61, "Rain"),
                new WeatherResponse.DailyForecast("2026-08-16", 28, 19, 0.0, 8, 0, "Clear sky"),
                new WeatherResponse.DailyForecast("2026-08-17", 29, 20, 0.0, 8, 0, "Clear sky")));
    }
}
