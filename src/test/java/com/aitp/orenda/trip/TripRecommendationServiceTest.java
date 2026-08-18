package com.aitp.orenda.trip;

import com.aitp.orenda.model.PoiResponse;
import com.aitp.orenda.preference.PreferenceService;
import com.aitp.orenda.preference.TripConstraints;
import com.aitp.orenda.repository.PoiRepository;
import com.aitp.orenda.weather.WeatherResponse;
import com.aitp.orenda.weather.WeatherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripRecommendationServiceTest {

    @Mock
    private PoiRepository poiRepository;

    @Mock
    private WeatherService weatherService;

    @Mock
    private TravelTimeEstimator travelTimeEstimator;

    @Mock
    private ItineraryOptimizer itineraryOptimizer;

    @Mock
    private PreferenceService preferenceService;

    @Mock
    private ItineraryNarrator itineraryNarrator;

    @Mock
    private LunchPlanner lunchPlanner;

    @InjectMocks
    private TripRecommendationService service;

    @BeforeEach
    void setUp() {
        lenient().when(travelTimeEstimator.minutesBetween(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(10.0);
        lenient().when(travelTimeEstimator.distanceKm(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(0.5);
        lenient().when(itineraryOptimizer.build(any(), any(), anyInt(), anyDouble(), anyDouble(), any()))
                .thenReturn(List.of());
        lenient().when(itineraryNarrator.narrate(any(), any(), any(), any(), any(), any(), any(), anyDouble()))
                .thenReturn(new ItineraryNarrator.NarrativeOutput("", List.of()));
        lenient().when(preferenceService.loadWeights(any()))
                .thenReturn(Map.of());
        lenient().when(preferenceService.loadConstraints(any()))
                .thenReturn(TripConstraints.NONE);
        lenient().when(preferenceService.insightFor(any()))
                .thenReturn(null);
        lenient().when(preferenceService.loadProfileInterests(any()))
                .thenReturn(List.of());
    }

    private TripPlanRequest sampleRequest() {
        return TripPlanRequest.builder()
                .basics(TripPlanRequest.TripBasics.builder()
                        .destination("Istanbul")
                        .startDate(LocalDate.of(2026, 8, 15))
                        .endDate(LocalDate.of(2026, 8, 18))
                        .travelerCount(2)
                        .accommodationLocation("41.0082,28.9784")
                        .arrivalTime("14:30")
                        .departureTime("11:00")
                        .transportMode(TripEnums.TransportMode.DRIVING)
                        .build())
                .profile(TripPlanRequest.TravelerProfile.builder()
                        .ageRange(TripEnums.AgeRange.AGE_25_34)
                        .groupType(TripEnums.GroupType.COUPLE)
                        .mobilityLimitation(TripEnums.MobilityLimitation.NONE)
                        .build())
                .interests(TripPlanRequest.Interests.builder()
                        .selectedInterests(List.of(TripEnums.Interest.HISTORY, TripEnums.Interest.MUSEUMS))
                        .additionalNotes("Love hidden gems")
                        .build())
                .style(TripPlanRequest.TravelStyle.builder()
                        .pace(TripEnums.Pace.BALANCED)
                        .walking(TripEnums.WalkingLevel.MODERATE)
                        .budget(TripEnums.Budget.MID_RANGE)
                        .food(TripEnums.FoodPreference.LOCAL)
                        .planningStyle(TripEnums.PlanningStyle.RECOMMENDATIONS_ONLY)
                        .build())
                .build();
    }

    private PoiResponse museumPoi() {
        return PoiResponse.builder()
                .id("11111111-1111-1111-1111-111111111111")
                .nameTr("Topkapı Palace")
                .category("historic")
                .subcategory("palace")
                .lat(41.0117)
                .lon(28.9833)
                .completenessScore(85)
                .distanceKm(1.5)
                .attributes(Map.of("tourism", "museum", "wikidata", "Q201297"))
                .build();
    }

    private PoiResponse barPoi() {
        return PoiResponse.builder()
                .id("22222222-2222-2222-2222-222222222222")
                .nameTr("Nightclub X")
                .category("entertainment")
                .subcategory("nightclub")
                .lat(41.0200)
                .lon(28.9900)
                .completenessScore(70)
                .distanceKm(3.0)
                .attributes(Map.of())
                .build();
    }

    @Test
    void recommends_museums_over_nightclubs_for_history_lovers() {
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(museumPoi(), barPoi()));

        TripPlanResponse resp = service.recommend(sampleRequest());

        assertThat(resp.getTripDays()).isEqualTo(4);
        assertThat(resp.getSuggestions()).isNotEmpty();
        // Museum should rank first for a history/museum interest
        assertThat(resp.getSuggestions().get(0).getPoi().getId())
                .isEqualTo("11111111-1111-1111-1111-111111111111");
    }

    @Test
    void recommends_families_with_children_highly() {
        TripPlanRequest familyReq = TripPlanRequest.builder()
                .basics(sampleRequest().getBasics())
                .profile(TripPlanRequest.TravelerProfile.builder()
                        .ageRange(sampleRequest().getProfile().getAgeRange())
                        .groupType(TripEnums.GroupType.FAMILY)
                        .mobilityLimitation(sampleRequest().getProfile().getMobilityLimitation())
                        .build())
                .interests(TripPlanRequest.Interests.builder()
                        .selectedInterests(List.of(TripEnums.Interest.FAMILY_ACTIVITIES))
                        .additionalNotes(sampleRequest().getInterests().getAdditionalNotes())
                        .build())
                .style(sampleRequest().getStyle())
                .build();

        PoiResponse zoo = PoiResponse.builder()
                .id("33333333-3333-3333-3333-333333333333")
                .nameTr("Istanbul Zoo")
                .category("leisure")
                .subcategory("zoo")
                .lat(41.0100)
                .lon(28.9800)
                .completenessScore(80)
                .distanceKm(2.0)
                .attributes(Map.of())
                .build();

        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(zoo, barPoi()));

        TripPlanResponse resp = service.recommend(familyReq);

        assertThat(resp.getSuggestions()).isNotEmpty();
        assertThat(resp.getSuggestions().get(0).getPoi().getId())
                .isEqualTo("33333333-3333-3333-3333-333333333333");
    }

    @Test
    void no_day_plan_when_recommendations_only() {
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(museumPoi()));

        TripPlanResponse resp = service.recommend(sampleRequest());

        assertThat(resp.getDayPlan()).isEmpty();
    }

    @Test
    void excludes_pois_closed_on_all_trip_dates() {
        PoiResponse closedAll = PoiResponse.builder()
                .id("44444444-4444-4444-4444-444444444444")
                .nameTr("Closed Museum")
                .category("tourism")
                .subcategory("museum")
                .lat(41.0050)
                .lon(28.9750)
                .completenessScore(80)
                .distanceKm(1.0)
                // Open only on Wednesdays — the trip (Sat-Tue) never overlaps.
                .attributes(Map.of("opening_hours", "We 09:00-18:00"))
                .build();

        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(closedAll, museumPoi()));

        TripPlanResponse resp = service.recommend(sampleRequest());

        assertThat(resp.getSuggestions()).noneMatch(s -> "44444444-4444-4444-4444-444444444444".equals(s.getPoi().getId()));
        assertThat(resp.getSuggestions()).isNotEmpty();
    }

    @Test
    void rainy_forecast_boosts_indoor_venues() {
        PoiResponse outdoor = PoiResponse.builder()
                .id("55555555-5555-5555-5555-555555555555")
                .nameTr("Gülhane Park")
                .category("leisure")
                .subcategory("park")
                .lat(41.0120)
                .lon(28.9810)
                .completenessScore(75)
                .distanceKm(1.0)
                .attributes(Map.of())
                .build();

        TripPlanRequest req = sampleRequest();
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(museumPoi(), outdoor));
        when(weatherService.getWeather(anyDouble(), anyDouble(), anyInt())).thenReturn(rainyForecast());

        TripPlanResponse resp = service.recommend(req);

        assertThat(resp.getWeatherSummary()).containsIgnoringCase("rain");
        assertThat(resp.getSuggestions().get(0).getPoi().getId())
                .isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(resp.getNotes()).anyMatch(n -> n.contains("Rain expected"));
    }

    @Test
    void learned_preferences_boost_matching_categories() {
        TripPlanRequest req = sampleRequest();
        req.setSessionId("session-1");
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(museumPoi(), barPoi()));
        // User loves culture (historic/palace → CULTURE), avoids nightlife.
        when(preferenceService.loadWeights("session-1"))
                .thenReturn(Map.of("CULTURE", 0.9, "NIGHTLIFE", 0.1));
        when(preferenceService.insightFor(any()))
                .thenReturn("I noticed you tend to prefer cultural experiences. I've adjusted your recommendations accordingly.");

        TripPlanResponse resp = service.recommend(req);

        assertThat(resp.getSuggestions().get(0).getPoi().getId())
                .isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(resp.getPreferenceInsight()).contains("cultural experiences");
    }

    @Test
    void falls_back_to_profile_interests_when_trip_has_none() {
        TripPlanRequest req = sampleRequest();
        req.setSessionId("session-1");
        req.getInterests().setSelectedInterests(List.of());
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(museumPoi(), barPoi()));
        when(preferenceService.loadProfileInterests("session-1"))
                .thenReturn(List.of(TripEnums.Interest.HISTORY, TripEnums.Interest.MUSEUMS));

        TripPlanResponse resp = service.recommend(req);

        assertThat(resp.getSuggestions().get(0).getPoi().getId())
                .isEqualTo("11111111-1111-1111-1111-111111111111");
    }

    @Test
    void family_safe_constraint_excludes_adult_venues() {
        TripPlanRequest req = sampleRequest();
        req.setSessionId("session-1");
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(museumPoi(), barPoi()));
        when(preferenceService.loadConstraints("session-1"))
                .thenReturn(new TripConstraints(null, null, false, true, false));

        TripPlanResponse resp = service.recommend(req);

        assertThat(resp.getSuggestions())
                .noneMatch(s -> "22222222-2222-2222-2222-222222222222".equals(s.getPoi().getId()));
        assertThat(resp.getNotes()).anyMatch(n -> n.contains("Adult-oriented"));
    }

    @Test
    void budget_cap_constraint_lowers_effective_budget() {
        TripPlanRequest req = sampleRequest();
        req.setSessionId("session-1");
        req.getStyle().setBudget(TripEnums.Budget.LUXURY);
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(museumPoi(), barPoi()));
        when(preferenceService.loadConstraints("session-1"))
                .thenReturn(new TripConstraints(TripEnums.Budget.MID_RANGE, null, false, false, false));

        TripPlanResponse resp = service.recommend(req);

        assertThat(resp.getNotes()).anyMatch(n -> n.contains("Budget capped at mid range"));
    }

    @Test
    void rainy_trip_excludes_open_roof_pois() {
        PoiResponse park = PoiResponse.builder()
                .id("55555555-5555-5555-5555-555555555555")
                .nameTr("Gülhane Park")
                .category("leisure")
                .subcategory("park")
                .lat(41.0120)
                .lon(28.9810)
                .completenessScore(75)
                .distanceKm(1.0)
                .attributes(Map.of())
                .build();

        TripPlanRequest req = sampleRequest();
        req.getBasics().setStartDate(LocalDate.of(2026, 8, 15));
        req.getBasics().setEndDate(LocalDate.of(2026, 8, 16));
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(museumPoi(), park));
        when(weatherService.getWeather(anyDouble(), anyDouble(), anyInt())).thenReturn(twoRainyDays());

        TripPlanResponse resp = service.recommend(req);

        assertThat(resp.getSuggestions())
                .noneMatch(s -> "55555555-5555-5555-5555-555555555555".equals(s.getPoi().getId()));
        assertThat(resp.getSuggestions()).isNotEmpty();
    }

    @Test
    void rooftop_restaurant_is_excluded_on_rainy_trip() {
        PoiResponse rooftopBar = PoiResponse.builder()
                .id("66666666-6666-6666-6666-666666666666")
                .nameTr("Rooftop Bar")
                .category("food_drink")
                .subcategory("bar")
                .lat(41.0200)
                .lon(28.9900)
                .completenessScore(80)
                .distanceKm(2.0)
                .attributes(Map.of())
                .build();

        TripPlanRequest req = sampleRequest();
        req.getBasics().setStartDate(LocalDate.of(2026, 8, 15));
        req.getBasics().setEndDate(LocalDate.of(2026, 8, 16));
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(museumPoi(), rooftopBar));
        when(weatherService.getWeather(anyDouble(), anyDouble(), anyInt())).thenReturn(twoRainyDays());

        TripPlanResponse resp = service.recommend(req);

        assertThat(resp.getSuggestions())
                .noneMatch(s -> "66666666-6666-6666-6666-666666666666".equals(s.getPoi().getId()));
    }

    @Test
    void attaches_lunch_block_to_day_plan() {
        TripPlanRequest req = sampleRequest();
        req.getStyle().setPlanningStyle(TripEnums.PlanningStyle.DETAILED_SCHEDULE);

        TripPlanResponse.ScoredPoi item = TripPlanResponse.ScoredPoi.builder()
                .poi(museumPoi())
                .score(80)
                .factors(Map.of())
                .reasons(List.of())
                .startTime("10:30")
                .endTime("12:30")
                .travelMinutes(10)
                .visitMinutes(120)
                .build();
        TripPlanResponse.DayPlan dayPlan = TripPlanResponse.DayPlan.builder()
                .day(1)
                .date("2026-08-15")
                .weather("Sunny, 28°C")
                .items(List.of(item))
                .notes(List.of())
                .build();
        TripPlanResponse.LunchSlot lunch = TripPlanResponse.LunchSlot.builder()
                .prompt("Lunch time — head back to the hotel, or should I pick a restaurant nearby?")
                .needsDietInfo(true)
                .nearbyRestaurants(List.of())
                .build();

        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(museumPoi()));
        when(itineraryOptimizer.build(any(), any(), anyInt(), anyDouble(), anyDouble(), any()))
                .thenReturn(List.of(dayPlan));
        when(lunchPlanner.plan(any(), any(), anyDouble(), anyDouble())).thenReturn(lunch);

        TripPlanResponse resp = service.recommend(req);

        assertThat(resp.getDayPlan()).hasSize(1);
        assertThat(resp.getDayPlan().get(0).getLunch()).isEqualTo(lunch);
    }

    @Test
    void no_weather_forecast_is_graceful() {
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(museumPoi()));
        when(weatherService.getWeather(anyDouble(), anyDouble(), anyInt()))
                .thenThrow(new RuntimeException("timeout"));

        TripPlanResponse resp = service.recommend(sampleRequest());

        assertThat(resp.getSuggestions()).isNotEmpty();
        assertThat(resp.getWeatherSummary()).isEmpty();
        assertThat(resp.getNotes()).noneMatch(n -> n.contains("Rain"));
    }

    @Test
    void empty_candidates_yield_hint_and_no_suggestions() {
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of());

        TripPlanResponse resp = service.recommend(sampleRequest());

        assertThat(resp.getSuggestions()).isEmpty();
        assertThat(resp.getSummary()).contains("No matching POIs");
        assertThat(resp.getNotes()).anyMatch(n -> n.contains("Few results"));
    }

    @Test
    void invalid_accommodation_location_falls_back_to_istanbul_centre() {
        TripPlanRequest req = sampleRequest();
        req.getBasics().setAccommodationLocation("banana");
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(museumPoi()));

        TripPlanResponse resp = service.recommend(req);

        assertThat(resp.getSuggestions()).isNotEmpty();
    }

    @Test
    void wheelchair_accessibility_ranks_above_non_accessible_same_category() {
        PoiResponse accessible = museumPoi();
        accessible.setId("11111111-1111-1111-1111-111111111111");
        accessible.setNameTr("Accessible Museum");
        accessible.setAttributes(Map.of("tourism", "museum", "wheelchair", "yes"));

        PoiResponse notAccessible = museumPoi();
        notAccessible.setId("22222222-2222-2222-2222-222222222222");
        notAccessible.setNameTr("Stairs-Only Museum");
        notAccessible.setAttributes(Map.of("tourism", "museum"));

        TripPlanRequest req = sampleRequest();
        req.getProfile().setMobilityLimitation(TripEnums.MobilityLimitation.WHEELCHAIR);
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(notAccessible, accessible));

        TripPlanResponse resp = service.recommend(req);

        assertThat(resp.getSuggestions().get(0).getPoi().getId())
                .isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(resp.getSuggestions().get(0).getReasons())
                .anyMatch(r -> r.contains("Wheelchair accessible"));
    }

    @Test
    void vegetarian_food_preference_boosts_vegetarian_restaurants() {
        PoiResponse veg = PoiResponse.builder()
                .id("11111111-1111-1111-1111-111111111111")
                .nameTr("Vegetarian Kitchen")
                .category("food_drink")
                .subcategory("restaurant")
                .lat(41.0110)
                .lon(28.9800)
                .completenessScore(70)
                .distanceKm(1.0)
                .attributes(Map.of("diet:vegetarian", "yes"))
                .build();
        PoiResponse kebab = PoiResponse.builder()
                .id("22222222-2222-2222-2222-222222222222")
                .nameTr("Kebab House")
                .category("food_drink")
                .subcategory("restaurant")
                .lat(41.0110)
                .lon(28.9800)
                .completenessScore(70)
                .distanceKm(1.0)
                .attributes(Map.of("cuisine", "kebab"))
                .build();

        TripPlanRequest req = sampleRequest();
        req.getStyle().setFood(TripEnums.FoodPreference.VEGETARIAN);
        req.getInterests().setSelectedInterests(List.of(TripEnums.Interest.FOOD));
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(kebab, veg));

        TripPlanResponse resp = service.recommend(req);

        assertThat(resp.getSuggestions().get(0).getPoi().getId())
                .isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(resp.getSuggestions().get(0).getReasons())
                .anyMatch(r -> r.contains("Vegetarian-friendly"));
    }

    @Test
    void partially_rainy_trip_keeps_open_roof_in_suggestions() {
        PoiResponse park = PoiResponse.builder()
                .id("55555555-5555-5555-5555-555555555555")
                .nameTr("Gülhane Park")
                .category("leisure")
                .subcategory("park")
                .lat(41.0120)
                .lon(28.9810)
                .completenessScore(75)
                .distanceKm(1.0)
                .attributes(Map.of())
                .build();

        TripPlanRequest req = sampleRequest();
        req.getBasics().setStartDate(LocalDate.of(2026, 8, 15));
        req.getBasics().setEndDate(LocalDate.of(2026, 8, 17));
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(museumPoi(), park));
        when(weatherService.getWeather(anyDouble(), anyDouble(), anyInt()))
                .thenReturn(oneRainyOfThree());

        TripPlanResponse resp = service.recommend(req);

        // Scoring keeps it (only 1 of 3 days is rainy); the per-day optimizer
        // decides not to schedule it on that single rainy day.
        assertThat(resp.getSuggestions())
                .anyMatch(s -> "55555555-5555-5555-5555-555555555555".equals(s.getPoi().getId()));
        assertThat(resp.getWeatherSummary()).containsIgnoringCase("rain");
    }

    @Test
    void lunch_block_survives_narration() {
        TripPlanRequest req = sampleRequest();
        req.getStyle().setPlanningStyle(TripEnums.PlanningStyle.DETAILED_SCHEDULE);

        TripPlanResponse.ScoredPoi item = TripPlanResponse.ScoredPoi.builder()
                .poi(museumPoi())
                .score(80)
                .factors(Map.of())
                .reasons(List.of())
                .startTime("10:30")
                .endTime("12:30")
                .travelMinutes(10)
                .visitMinutes(120)
                .build();
        TripPlanResponse.DayPlan dayPlan = TripPlanResponse.DayPlan.builder()
                .day(1)
                .date("2026-08-15")
                .weather("Sunny, 28°C")
                .items(List.of(item))
                .notes(List.of())
                .build();
        TripPlanResponse.LunchSlot lunch = TripPlanResponse.LunchSlot.builder()
                .prompt("Lunch time — head back to the hotel, or should I pick a restaurant nearby?")
                .needsDietInfo(true)
                .nearbyRestaurants(List.of())
                .build();

        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(museumPoi()));
        when(itineraryOptimizer.build(any(), any(), anyInt(), anyDouble(), anyDouble(), any()))
                .thenReturn(List.of(dayPlan));
        when(lunchPlanner.plan(any(), any(), anyDouble(), anyDouble())).thenReturn(lunch);
        when(itineraryNarrator.narrate(any(), any(), any(), any(), any(), any(), any(), anyDouble()))
                .thenReturn(new ItineraryNarrator.NarrativeOutput("Overall story", List.of("Day 1 story")));

        TripPlanResponse resp = service.recommend(req);

        TripPlanResponse.DayPlan day = resp.getDayPlan().get(0);
        assertThat(day.getLunch()).isEqualTo(lunch);
        assertThat(day.getNarrative()).isEqualTo("Day 1 story");
        assertThat(resp.getNarrative()).isEqualTo("Overall story");
    }

    private WeatherResponse oneRainyOfThree() {
        var loc = new WeatherResponse.Location(41.0082, 28.9784, "Europe/Istanbul");
        var current = new WeatherResponse.CurrentConditions(22, 23, 80, 12, 180, 61, "Slight rain");
        return new WeatherResponse(loc, current, List.of(
                new WeatherResponse.DailyForecast("2026-08-15", 22, 17, 8.0, 22, 61, "Rain"),
                new WeatherResponse.DailyForecast("2026-08-16", 28, 19, 0.0, 8, 0, "Clear sky"),
                new WeatherResponse.DailyForecast("2026-08-17", 29, 20, 0.0, 8, 0, "Clear sky")));
    }

    private WeatherResponse rainyForecast() {
        var loc = new WeatherResponse.Location(41.0082, 28.9784, "Europe/Istanbul");
        var current = new WeatherResponse.CurrentConditions(22, 23, 80, 12, 180, 61, "Slight rain");
        var day = new WeatherResponse.DailyForecast("2026-08-15", 24, 18, 8.0, 20, 61, "Slight rain");
        return new WeatherResponse(loc, current, List.of(day));
    }

    private WeatherResponse twoRainyDays() {
        var loc = new WeatherResponse.Location(41.0082, 28.9784, "Europe/Istanbul");
        var current = new WeatherResponse.CurrentConditions(20, 21, 85, 15, 200, 61, "Rain");
        var d1 = new WeatherResponse.DailyForecast("2026-08-15", 22, 17, 8.0, 22, 61, "Rain");
        var d2 = new WeatherResponse.DailyForecast("2026-08-16", 21, 16, 10.0, 24, 63, "Rain");
        return new WeatherResponse(loc, current, List.of(d1, d2));
    }
}