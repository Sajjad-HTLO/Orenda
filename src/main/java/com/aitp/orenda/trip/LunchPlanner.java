package com.aitp.orenda.trip;

import com.aitp.orenda.model.PoiResponse;
import com.aitp.orenda.repository.PoiRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Builds the interactive lunch-time block for each day of the itinerary.
 * <p>
 * Around the lunch window the app asks the traveler whether to head back to the
 * hotel or eat nearby. When the traveler's diet is unknown it flags the block
 * with {@code needsDietInfo = true} so the client can pop up a diet question,
 * and once the diet is known it recommends restaurants near the traveler's
 * lunch-time location, filtered by that diet.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LunchPlanner {

    private static final double LUNCH_RADIUS_KM = 2.0;
    private static final int LUNCH_LIMIT = 6;
    private static final int LUNCH_VISIT_MINUTES = 60;
    private static final LocalTime LUNCH_AFTER = LocalTime.of(11, 30);
    private static final LocalTime LUNCH_BEFORE = LocalTime.of(14, 30);

    private static final List<String> FOOD_SUBCATEGORIES =
            List.of("restaurant", "cafe", "fast_food", "food_court");

    private final PoiRepository poiRepository;
    private final TravelTimeEstimator travelTimeEstimator;

    /**
     * Lunch block for one day. Assumes the traveler is wherever the itinerary
     * has them at midday (the stop whose end time is closest to 13:00), falling
     * back to the hotel when the day has no stops.
     */
    public TripPlanResponse.LunchSlot plan(TripPlanRequest req,
                                           TripPlanResponse.DayPlan day,
                                           double baseLat, double baseLon) {
        List<TripPlanResponse.ScoredPoi> items = day.getItems();
        if (dayHasLunch(items)) {
            return TripPlanResponse.LunchSlot.builder()
                    .prompt("Lunch is already part of your schedule.")
                    .needsDietInfo(false)
                    .nearbyRestaurants(List.of())
                    .returnToHotel(null)
                    .note(null)
                    .build();
        }

        TripEnums.Diet diet = effectiveDiet(req);
        boolean needsDiet = req.getProfile().getDiet() == null && diet == TripEnums.Diet.NONE;

        double[] lunch = lunchLocation(items, baseLat, baseLon);
        double lunchLat = lunch[0];
        double lunchLon = lunch[1];

        TripEnums.TransportMode mode = req.getBasics().getTransportMode();
        List<TripPlanResponse.ScoredPoi> restaurants = nearRestaurants(lunchLat, lunchLon, diet, mode);

        int backMinutes = (int) Math.round(travelTimeEstimator.minutesBetween(
                lunchLat, lunchLon, baseLat, baseLon, mode));
        double backKm = travelTimeEstimator.distanceKm(lunchLat, lunchLon, baseLat, baseLon);

        return TripPlanResponse.LunchSlot.builder()
                .prompt("Lunch time — head back to the hotel, or should I pick a restaurant nearby?")
                .needsDietInfo(needsDiet)
                .nearbyRestaurants(restaurants)
                .returnToHotel(TripPlanResponse.ReturnToHotel.builder()
                        .travelMinutes(backMinutes)
                        .distanceKm(Math.round(backKm * 10.0) / 10.0)
                        .build())
                .note(noteFor(req, diet, restaurants))
                .build();
    }

    private List<TripPlanResponse.ScoredPoi> nearRestaurants(double lat, double lon,
                                                             TripEnums.Diet diet,
                                                             TripEnums.TransportMode mode) {
        try {
            List<PoiResponse> food = poiRepository.findNearby(lat, lon, LUNCH_RADIUS_KM,
                    "food_drink", 0, LUNCH_LIMIT * 4);
            return food.stream()
                    .filter(this::isLunchSuitable)
                    .sorted(Comparator
                            .comparing((PoiResponse p) -> DietMatcher.matches(p, diet) ? 0 : 1)
                            .thenComparing(p -> p.getDistanceKm() == null ? Double.MAX_VALUE : p.getDistanceKm()))
                    .limit(LUNCH_LIMIT)
                    .map(p -> toScored(p, diet, lat, lon, mode))
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to find lunch restaurants near ({}, {}): {}", lat, lon, e.getMessage());
            return List.of();
        }
    }

    private TripPlanResponse.ScoredPoi toScored(PoiResponse poi, TripEnums.Diet diet,
                                                double lat, double lon,
                                                TripEnums.TransportMode mode) {
        boolean matches = DietMatcher.matches(poi, diet);
        double distanceKm = poi.getDistanceKm() == null ? 0 : poi.getDistanceKm();
        int travel = (int) Math.round(travelTimeEstimator.minutesBetween(
                lat, lon, poi.getLat(), poi.getLon(), mode));
        double proximity = Math.max(0, 15 * (1 - distanceKm / 2.0));

        List<String> reasons = new ArrayList<>();
        if (matches && diet != null && diet != TripEnums.Diet.NONE) {
            reasons.add("Fits your " + DietMatcher.description(diet) + " diet");
        }
        if (distanceKm > 0) {
            reasons.add("About %.1f km / %d min from your stop".formatted(distanceKm, travel));
        }

        return TripPlanResponse.ScoredPoi.builder()
                .poi(poi)
                .score(Math.round((75 + proximity + (matches ? 10 : 0)) * 100.0) / 100.0)
                .factors(Map.of("diet_match", matches ? 10.0 : 0.0,
                        "proximity", proximity))
                .reasons(reasons)
                .travelMinutes(travel)
                .visitMinutes(LUNCH_VISIT_MINUTES)
                .build();
    }

    /**
     * Where the traveler is at lunch: the scheduled stop whose end time lands
     * closest to 13:00, or the hotel when the day has no stops.
     */
    private double[] lunchLocation(List<TripPlanResponse.ScoredPoi> items,
                                   double baseLat, double baseLon) {
        if (items == null || items.isEmpty()) {
            return new double[]{baseLat, baseLon};
        }
        TripPlanResponse.ScoredPoi lunchStop = items.stream()
                .min(Comparator.comparingInt(s -> middayOffsetMinutes(s.getEndTime())))
                .orElse(null);
        if (lunchStop == null) {
            return new double[]{baseLat, baseLon};
        }
        return new double[]{lunchStop.getPoi().getLat(), lunchStop.getPoi().getLon()};
    }

    private int middayOffsetMinutes(String endTime) {
        if (endTime == null) {
            return Integer.MAX_VALUE;
        }
        try {
            LocalTime t = LocalTime.parse(endTime);
            return Math.abs(t.toSecondOfDay() - LocalTime.of(13, 0).toSecondOfDay()) / 60;
        } catch (DateTimeParseException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * True when the day already schedules a food stop inside the lunch window —
     * then no separate lunch question is needed.
     */
    private boolean dayHasLunch(List<TripPlanResponse.ScoredPoi> items) {
        if (items == null || items.isEmpty()) {
            return false;
        }
        return items.stream().anyMatch(s -> {
            String sub = s.getPoi().getSubcategory();
            if (sub == null || !FOOD_SUBCATEGORIES.contains(sub)) {
                return false;
            }
            if (s.getStartTime() == null) {
                return true;
            }
            try {
                LocalTime start = LocalTime.parse(s.getStartTime());
                return !start.isBefore(LUNCH_AFTER) && !start.isAfter(LUNCH_BEFORE);
            } catch (DateTimeParseException e) {
                return true;
            }
        });
    }

    private boolean isLunchSuitable(PoiResponse poi) {
        String sub = poi.getSubcategory();
        return sub != null && FOOD_SUBCATEGORIES.contains(sub);
    }

    private TripEnums.Diet effectiveDiet(TripPlanRequest req) {
        TripEnums.Diet diet = req.getProfile().getDiet();
        if (diet != null && diet != TripEnums.Diet.NONE) {
            return diet;
        }
        // Fall back to the food preference when it implies a restriction.
        return switch (req.getStyle().getFood()) {
            case VEGETARIAN -> TripEnums.Diet.VEGETARIAN;
            case VEGAN -> TripEnums.Diet.VEGAN;
            default -> TripEnums.Diet.NONE;
        };
    }

    private String noteFor(TripPlanRequest req, TripEnums.Diet diet,
                           List<TripPlanResponse.ScoredPoi> restaurants) {
        boolean needsDiet = req.getProfile().getDiet() == null && diet == TripEnums.Diet.NONE;
        if (needsDiet) {
            return "We don't know your dietary needs yet — tell us and I'll pick better restaurants.";
        }
        if (restaurants.isEmpty()) {
            return diet == TripEnums.Diet.NONE
                    ? "No restaurants found within walking distance of your lunch stop."
                    : "No " + DietMatcher.description(diet)
                    + " options found nearby — showing the closest restaurants.";
        }
        long matched = restaurants.stream()
                .filter(r -> DietMatcher.matches(r.getPoi(), diet))
                .count();
        if (diet != TripEnums.Diet.NONE && matched == 0) {
            return "No " + DietMatcher.description(diet)
                    + " options found nearby — showing the closest restaurants.";
        }
        if (diet != TripEnums.Diet.NONE) {
            return matched + " " + DietMatcher.description(diet) + "-friendly place"
                    + (matched == 1 ? "" : "s") + " near your lunch stop.";
        }
        return restaurants.size() + " restaurant" + (restaurants.size() == 1 ? "" : "s")
                + " within walking distance of your lunch stop.";
    }
}
