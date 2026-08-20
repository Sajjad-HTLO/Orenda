package com.aitp.orenda.trip;

import com.aitp.orenda.model.PoiResponse;
import com.aitp.orenda.repository.PoiRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LunchPlannerTest {

    @Mock
    private PoiRepository poiRepository;

    @Mock
    private TravelTimeEstimator travelTimeEstimator;

    private LunchPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new LunchPlanner(poiRepository, travelTimeEstimator);
        lenient().when(travelTimeEstimator.minutesBetween(anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                org.mockito.ArgumentMatchers.any())).thenReturn(8.0);
        lenient().when(travelTimeEstimator.distanceKm(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(0.4);
    }

    @Test
    void unknown_diet_flags_needs_diet_info_and_asks_hotel_vs_nearby() {
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), eq("food_drink"), anyInt(), anyInt()))
                .thenReturn(List.of(restaurant("1", "restaurant", Map.of(), 0.3)));

        TripPlanRequest req = request(null);
        TripPlanResponse.DayPlan day = dayPlan(stop("museum", "10:30", "12:00"));

        TripPlanResponse.LunchSlot lunch = planner.plan(req, day, 41.0082, 28.9784);

        assertThat(lunch.getNeedsDietInfo()).isTrue();
        assertThat(lunch.getPrompt()).contains("head back to the hotel");
        assertThat(lunch.getNearbyRestaurants()).hasSize(1);
        assertThat(lunch.getReturnToHotel().getTravelMinutes()).isEqualTo(8);
    }

    @Test
    void vegetarian_diet_only_suggests_vegetarian_restaurants() {
        PoiResponse veg = restaurant("1", "restaurant", Map.of("diet:vegetarian", "yes"), 0.2);
        PoiResponse kebab = restaurant("2", "restaurant", Map.of("cuisine", "kebab"), 0.1);
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), eq("food_drink"), anyInt(), anyInt()))
                .thenReturn(List.of(kebab, veg));

        TripPlanRequest req = request(TripEnums.Diet.VEGETARIAN);
        TripPlanResponse.DayPlan day = dayPlan(stop("museum", "10:30", "12:00"));

        TripPlanResponse.LunchSlot lunch = planner.plan(req, day, 41.0082, 28.9784);

        assertThat(lunch.getNeedsDietInfo()).isFalse();
        assertThat(lunch.getNearbyRestaurants()).hasSize(2);
        // Diet match ranks first even though the kebab place is closer.
        assertThat(lunch.getNearbyRestaurants().get(0).getPoi().getId()).isEqualTo(veg.getId());
        assertThat(lunch.getNearbyRestaurants().get(0).getReasons())
                .anyMatch(r -> r.contains("vegetarian"));
    }

    @Test
    void bars_and_ice_cream_are_not_lunch_suggestions() {
        PoiResponse restaurant = restaurant("1", "restaurant", Map.of(), 0.2);
        PoiResponse bar = restaurant("2", "bar", Map.of(), 0.1);
        PoiResponse iceCream = restaurant("3", "ice_cream", Map.of(), 0.1);
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), eq("food_drink"), anyInt(), anyInt()))
                .thenReturn(List.of(bar, iceCream, restaurant));

        TripPlanRequest req = request(TripEnums.Diet.NONE);
        TripPlanResponse.DayPlan day = dayPlan(stop("museum", "10:30", "12:00"));

        TripPlanResponse.LunchSlot lunch = planner.plan(req, day, 41.0082, 28.9784);

        assertThat(lunch.getNearbyRestaurants())
                .extracting(r -> r.getPoi().getSubcategory())
                .containsExactly("restaurant");
    }

    @Test
    void day_with_lunch_stop_needs_no_lunch_block() {
        TripPlanRequest req = request(TripEnums.Diet.NONE);
        TripPlanResponse.DayPlan day = dayPlan(stop("restaurant", "12:30", "13:30"));

        TripPlanResponse.LunchSlot lunch = planner.plan(req, day, 41.0082, 28.9784);

        assertThat(lunch.getNearbyRestaurants()).isEmpty();
        assertThat(lunch.getNeedsDietInfo()).isFalse();
        assertThat(lunch.getPrompt()).contains("already part of your schedule");
    }

    @Test
    void empty_day_uses_hotel_as_lunch_location() {
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), eq("food_drink"), anyInt(), anyInt()))
                .thenReturn(List.of(restaurant("1", "restaurant", Map.of(), 0.3)));

        TripPlanRequest req = request(TripEnums.Diet.NONE);
        TripPlanResponse.DayPlan day = dayPlan(List.of());

        TripPlanResponse.LunchSlot lunch = planner.plan(req, day, 41.0082, 28.9784);

        assertThat(lunch.getNearbyRestaurants()).hasSize(1);
        assertThat(lunch.getReturnToHotel().getTravelMinutes()).isEqualTo(8);
    }

    @Test
    void no_matching_diet_options_shows_closest_and_notes_it() {
        PoiResponse kebab = restaurant("1", "restaurant", Map.of("cuisine", "kebab"), 0.2);
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), eq("food_drink"), anyInt(), anyInt()))
                .thenReturn(List.of(kebab));

        TripPlanRequest req = request(TripEnums.Diet.VEGAN);
        TripPlanResponse.DayPlan day = dayPlan(stop("museum", "10:30", "12:00"));

        TripPlanResponse.LunchSlot lunch = planner.plan(req, day, 41.0082, 28.9784);

        assertThat(lunch.getNearbyRestaurants()).hasSize(1);
        assertThat(lunch.getNote()).contains("No vegan options found nearby");
    }

    @Test
    void diet_is_inferred_from_food_preference_when_not_set_explicitly() {
        PoiResponse veg = restaurant("1", "restaurant", Map.of("diet:vegetarian", "yes"), 0.2);
        PoiResponse kebab = restaurant("2", "restaurant", Map.of("cuisine", "kebab"), 0.1);
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), eq("food_drink"), anyInt(), anyInt()))
                .thenReturn(List.of(kebab, veg));

        // No diet field, but food preference = VEGETARIAN → inferred.
        TripPlanRequest req = request(null, TripEnums.FoodPreference.VEGETARIAN);
        TripPlanResponse.DayPlan day = dayPlan(stop("museum", "10:30", "12:00"));

        TripPlanResponse.LunchSlot lunch = planner.plan(req, day, 41.0082, 28.9784);

        assertThat(lunch.getNeedsDietInfo()).isFalse();
        assertThat(lunch.getNearbyRestaurants().get(0).getPoi().getId()).isEqualTo(veg.getId());
    }

    @Test
    void explicit_diet_none_means_the_question_was_answered() {
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), eq("food_drink"), anyInt(), anyInt()))
                .thenReturn(List.of(restaurant("1", "restaurant", Map.of(), 0.3)));

        TripPlanRequest req = request(TripEnums.Diet.NONE);
        TripPlanResponse.DayPlan day = dayPlan(stop("museum", "10:30", "12:00"));

        TripPlanResponse.LunchSlot lunch = planner.plan(req, day, 41.0082, 28.9784);

        // "None" is an answer — no pop-up needed, restaurants unrestricted.
        assertThat(lunch.getNeedsDietInfo()).isFalse();
        assertThat(lunch.getNearbyRestaurants()).hasSize(1);
    }

    @Test
    void no_restaurants_nearby_returns_empty_and_notes_it() {
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), eq("food_drink"), anyInt(), anyInt()))
                .thenReturn(List.of());

        TripPlanRequest req = request(TripEnums.Diet.NONE);
        TripPlanResponse.DayPlan day = dayPlan(stop("museum", "10:30", "12:00"));

        TripPlanResponse.LunchSlot lunch = planner.plan(req, day, 41.0082, 28.9784);

        assertThat(lunch.getNearbyRestaurants()).isEmpty();
        assertThat(lunch.getNote()).contains("No restaurants found");
    }

    @Test
    void lunch_location_is_the_stop_closest_to_midday() {
        when(poiRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), eq("food_drink"), anyInt(), anyInt()))
                .thenReturn(List.of(restaurant("1", "restaurant", Map.of(), 0.3)));

        // Stop A ends 12:00 (right at lunch), stop B ends 16:30 (too late).
        TripPlanResponse.ScoredPoi morning = stop("museum", "10:00", "12:00");
        morning.getPoi().setLat(41.1000);
        morning.getPoi().setLon(28.9000);
        TripPlanResponse.ScoredPoi afternoon = stop("castle", "14:00", "16:30");
        afternoon.getPoi().setLat(41.2000);
        afternoon.getPoi().setLon(28.8000);

        TripPlanRequest req = request(TripEnums.Diet.NONE);
        TripPlanResponse.DayPlan day = dayPlan(List.of(afternoon, morning));

        planner.plan(req, day, 41.0082, 28.9784);

        var captor = ArgumentCaptor.forClass(Double.class);
        Mockito.verify(poiRepository)
                .findNearby(captor.capture(), captor.capture(), captor.capture(), eq("food_drink"), anyInt(), anyInt());
        List<Double> args = captor.getAllValues();
        // Restaurants are searched around the stop closest to midday (morning stop).
        assertThat(args.get(0)).isEqualTo(41.1000);
        assertThat(args.get(1)).isEqualTo(28.9000);
    }

    private TripPlanRequest request(TripEnums.Diet diet) {
        return request(diet, TripEnums.FoodPreference.LOCAL);
    }

    private TripPlanRequest request(TripEnums.Diet diet, TripEnums.FoodPreference food) {
        return TripPlanRequest.builder()
                .basics(TripPlanRequest.TripBasics.builder()
                        .destination("Istanbul")
                        .startDate(LocalDate.of(2026, 8, 15))
                        .endDate(LocalDate.of(2026, 8, 15))
                        .travelerCount(2)
                        .accommodationLocation("41.0082,28.9784")
                        .transportMode(TripEnums.TransportMode.FOOT)
                        .build())
                .profile(TripPlanRequest.TravelerProfile.builder()
                        .ageRange(TripEnums.AgeRange.AGE_25_34)
                        .groupType(TripEnums.GroupType.COUPLE)
                        .mobilityLimitation(TripEnums.MobilityLimitation.NONE)
                        .diet(diet)
                        .build())
                .interests(TripPlanRequest.Interests.builder()
                        .selectedInterests(List.of(TripEnums.Interest.FOOD))
                        .build())
                .style(TripPlanRequest.TravelStyle.builder()
                        .pace(TripEnums.Pace.BALANCED)
                        .walking(TripEnums.WalkingLevel.MODERATE)
                        .budget(TripEnums.Budget.MID_RANGE)
                        .food(food)
                        .planningStyle(TripEnums.PlanningStyle.DETAILED_SCHEDULE)
                        .build())
                .build();
    }

    private TripPlanResponse.DayPlan dayPlan(TripPlanResponse.ScoredPoi item) {
        return dayPlan(item == null ? List.of() : List.of(item));
    }

    private TripPlanResponse.DayPlan dayPlan(List<TripPlanResponse.ScoredPoi> items) {
        return TripPlanResponse.DayPlan.builder()
                .day(1)
                .date("2026-08-15")
                .weather("Sunny, 28°C")
                .items(items)
                .notes(List.of())
                .build();
    }

    private TripPlanResponse.ScoredPoi stop(String subcategory, String start, String end) {
        return TripPlanResponse.ScoredPoi.builder()
                .poi(PoiResponse.builder()
                        .id("s-" + subcategory)
                        .nameTr("Stop " + subcategory)
                        .category(subcategory.equals("museum") ? "culture" : "food_drink")
                        .subcategory(subcategory)
                        .lat(41.01)
                        .lon(28.98)
                        .completenessScore(80)
                        .attributes(Map.of())
                        .build())
                .startTime(start)
                .endTime(end)
                .build();
    }

    private PoiResponse restaurant(String id, String subcategory, Map<String, Object> attrs, double distanceKm) {
        return PoiResponse.builder()
                .id(id)
                .nameTr("Restaurant " + id)
                .category("food_drink")
                .subcategory(subcategory)
                .lat(41.01)
                .lon(28.98)
                .completenessScore(70)
                .distanceKm(distanceKm)
                .attributes(attrs)
                .build();
    }
}
