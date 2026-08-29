package com.aitp.orenda.trip;

import com.aitp.orenda.model.PoiResponse;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Orders the scored POIs into a day-by-day itinerary.
 * <p>
 * Applies the last pipeline stages:
 * <ul>
 *   <li><b>Walking constraints</b> — daily walking-distance budget scaled by the
 *       traveler's walking level (and tightened for families with children).</li>
 *   <li><b>Budget</b> — per-day travel-time budget so days don't overrun.</li>
 *   <li><b>Opening hours</b> — only places open on the given date are scheduled.</li>
 *   <li><b>Weather</b> — indoor venues are placed on rainy days, outdoor on clear.</li>
 *   <li><b>Optimization</b> — nearest-neighbour ordering from the base reduces
 *       travel between stops; each item is assigned arrival/visit times.</li>
 * </ul>
 */
@Component
public class ItineraryOptimizer {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final LocalTime DAY_START = LocalTime.of(9, 30);

    private final TravelTimeEstimator travelTimeEstimator;

    public ItineraryOptimizer(TravelTimeEstimator travelTimeEstimator) {
        this.travelTimeEstimator = travelTimeEstimator;
    }

    public List<TripPlanResponse.DayPlan> build(
            List<TripPlanResponse.ScoredPoi> scored,
            TripPlanRequest req,
            int days,
            double baseLat, double baseLon,
            TripWeather weather) {
        return build(scored, req, days, baseLat, baseLon, weather, null, null);
    }

    /**
     * Same as {@link #build(List, TripPlanRequest, int, double, double, TripWeather)}
     * but honours the traveler's arrival / departure times:
     * <ul>
     *   <li>{@code firstDayStart} — day 1 starts at this time (e.g. hotel check-in
     *       after the flight arrives) instead of the default 09:30.</li>
     *   <li>{@code lastDayEnd} — the final day's itinerary ends by this time
     *       (e.g. departure from the city). Stops that would end after it are
     *       dropped.</li>
     * </ul>
     */
    public List<TripPlanResponse.DayPlan> build(
            List<TripPlanResponse.ScoredPoi> scored,
            TripPlanRequest req,
            int days,
            double baseLat, double baseLon,
            TripWeather weather,
            LocalTime firstDayStart, LocalTime lastDayEnd) {

        if (req.getStyle().getPlanningStyle() == TripEnums.PlanningStyle.RECOMMENDATIONS_ONLY) {
            return Collections.emptyList();
        }

        int poisPerDay = switch (req.getStyle().getPace()) {
            case RELAXED -> 2;
            case BALANCED -> 3;
            case PACKED -> 5;
        };
        double maxWalkKm = dailyWalkBudgetKm(req);
        boolean footMode = req.getBasics().getTransportMode() == TripEnums.TransportMode.FOOT;
        double dayTravelBudget = dailyTravelBudgetMinutes(req);
        TripEnums.TransportMode mode = req.getBasics().getTransportMode();
        LocalDate start = req.getBasics().getStartDate();

        List<TripPlanResponse.ScoredPoi> remaining = new ArrayList<>(scored);
        List<TripPlanResponse.DayPlan> plan = new ArrayList<>();

        for (int d = 1; d <= days && !remaining.isEmpty(); d++) {
            LocalDate date = start.plusDays(d - 1);
            boolean rainy = weather.isRainy(date.toString());
            boolean outdoorGood = weather.isOutdoorGood(date.toString());
            LocalTime dayStart = (d == 1 && firstDayStart != null) ? firstDayStart : DAY_START;
            LocalTime dayEnd = (d == days && lastDayEnd != null) ? lastDayEnd : null;

            List<TripPlanResponse.ScoredPoi> dayPool = pickDayPool(remaining, date, rainy, outdoorGood, poisPerDay);

            List<TripPlanResponse.ScoredPoi> dayItems = scheduleDay(dayPool, req, date, mode,
                    baseLat, baseLon, footMode, maxWalkKm, dayTravelBudget, poisPerDay, dayStart, dayEnd);

            remaining.removeAll(dayItems);

            List<String> notes = dayNotes(dayItems, date, rainy, footMode, maxWalkKm, mode);
            if (d == 1 && firstDayStart != null) {
                notes.add("Arriving at " + firstDayStart.format(TIME) + " — day 1 starts after arrival.");
            }
            if (d == days && lastDayEnd != null) {
                notes.add("Departing at " + lastDayEnd.format(TIME) + " — day " + days + " ends by then.");
            }
            plan.add(TripPlanResponse.DayPlan.builder()
                    .day(d)
                    .date(date.toString())
                    .weather(weather.description(date.toString()))
                    .items(dayItems)
                    .notes(notes)
                    .build());
        }
        return plan;
    }

    /**
     * Candidates for one day: not yet scheduled, open on the date (unknown hours
     * are kept), ordered by score boosted by weather fit.
     */
    private List<TripPlanResponse.ScoredPoi> pickDayPool(List<TripPlanResponse.ScoredPoi> remaining,
                                                         LocalDate date, boolean rainy,
                                                         boolean outdoorGood, int poisPerDay) {
        List<TripPlanResponse.ScoredPoi> pool = remaining.stream()
                .filter(s -> !(rainy && TripWeather.isOpenRoof(s.getPoi())))
                .filter(s -> isOpenOn(s.getPoi(), date))
                .sorted(Comparator.comparingDouble(
                        (TripPlanResponse.ScoredPoi s) -> dayFitScore(s, rainy, outdoorGood)).reversed())
                .limit(poisPerDay * 2L)
                .toList();
        if (pool.isEmpty()) {
            // Nothing reliably open today; fall back to best remaining POIs
            // (still keeping open-roof venues off rainy days).
            return remaining.stream()
                    .filter(s -> !(rainy && TripWeather.isOpenRoof(s.getPoi())))
                    .limit(poisPerDay * 2L)
                    .toList();
        }
        return pool;
    }

    private List<TripPlanResponse.ScoredPoi> scheduleDay(List<TripPlanResponse.ScoredPoi> dayPool,
                                                         TripPlanRequest req, LocalDate date,
                                                         TripEnums.TransportMode mode,
                                                         double baseLat, double baseLon,
                                                         boolean footMode, double maxWalkKm,
                                                         double dayTravelBudget, int poisPerDay,
                                                         LocalTime dayStart, LocalTime dayEnd) {
        List<TripPlanResponse.ScoredPoi> dayItems = new ArrayList<>();
        List<TripPlanResponse.ScoredPoi> pool = new ArrayList<>(dayPool);

        double prevLat = baseLat;
        double prevLon = baseLon;
        double walkedKm = 0;
        double totalTravel = 0;
        LocalTime current = dayStart;

        while (!pool.isEmpty() && dayItems.size() < poisPerDay) {
            TripPlanResponse.ScoredPoi next = nearest(pool, prevLat, prevLon, mode);
            if (next == null) {
                break;
            }
            pool.remove(next);

            double travel = travelTimeEstimator.minutesBetween(prevLat, prevLon,
                    next.getPoi().getLat(), next.getPoi().getLon(), mode);
            double legKm = travelTimeEstimator.distanceKm(prevLat, prevLon,
                    next.getPoi().getLat(), next.getPoi().getLon());

            if (footMode && walkedKm + legKm > maxWalkKm) {
                continue; // walking budget exceeded; try the next nearest instead
            }
            if (!dayItems.isEmpty()) {
                if (travel > dayTravelBudget) {
                    continue; // a single leg is too long for this trip's pace
                }
                // The itinerary is anchored on the hotel: the day's travel loop
                // (hotel → stops → hotel) must fit the travel budget, so the
                // return leg to the base counts too.
                double returnLeg = travelTimeEstimator.minutesBetween(
                        next.getPoi().getLat(), next.getPoi().getLon(), baseLat, baseLon, mode);
                if (totalTravel + travel + returnLeg > dayTravelBudget) {
                    continue;
                }
            }

            int visit = visitMinutes(next.getPoi());
            LocalTime arrival = current.plusMinutes(Math.max(1, Math.round(travel)));
            LocalTime end = arrival.plusMinutes(visit);
            if (dayEnd != null && end.isAfter(dayEnd)) {
                continue; // ends after departure — try a shorter visit instead
            }

            dayItems.add(copyWithSchedule(next, travel, visit, arrival, end, date));
            walkedKm += legKm;
            totalTravel += travel;
            current = end;
            prevLat = next.getPoi().getLat();
            prevLon = next.getPoi().getLon();
        }
        return dayItems;
    }

    private TripPlanResponse.ScoredPoi nearest(List<TripPlanResponse.ScoredPoi> pool,
                                               double fromLat, double fromLon,
                                               TripEnums.TransportMode mode) {
        return pool.stream()
                .min(Comparator.comparingDouble(s -> travelTimeEstimator.minutesBetween(
                        fromLat, fromLon, s.getPoi().getLat(), s.getPoi().getLon(), mode)))
                .orElse(null);
    }

    private TripPlanResponse.ScoredPoi copyWithSchedule(TripPlanResponse.ScoredPoi src, double travel,
                                                        int visit, LocalTime arrival, LocalTime end,
                                                        LocalDate date) {
        return TripPlanResponse.ScoredPoi.builder()
                .poi(src.getPoi())
                .score(src.getScore())
                .factors(src.getFactors())
                .reasons(src.getReasons())
                .travelMinutes((int) Math.round(travel))
                .visitMinutes(visit)
                .startTime(arrival.format(TIME))
                .endTime(end.format(TIME))
                .openAtScheduledTime(openingStatus(src.getPoi(), date, arrival.getHour()) != OpeningHoursEvaluator.OpeningStatus.CLOSED)
                .build();
    }

    /**
     * Weather-aware tie-break used to pick which POIs land on a given day:
     * indoor venues win on rainy days, outdoor venues win on clear days.
     */
    private double dayFitScore(TripPlanResponse.ScoredPoi s, boolean rainy, boolean outdoorGood) {
        double base = s.getScore();
        boolean indoor = TripWeather.isIndoor(s.getPoi());
        if (rainy) {
            return base + (indoor ? 10 : -10);
        }
        if (outdoorGood) {
            return base + (indoor ? 0 : 5);
        }
        return base;
    }

    private boolean isOpenOn(PoiResponse poi, LocalDate date) {
        OpeningHoursEvaluator.OpeningStatus status = openingStatusAcrossDay(poi, date);
        return status != OpeningHoursEvaluator.OpeningStatus.CLOSED;
    }

    /**
     * A POI is considered open on a date if it is open at any of several
     * representative daytime hours (11:00 / 14:00 / 16:00). Unknown hours are
     * treated as open so the planner never excludes data-poor POIs blindly.
     */
    private OpeningHoursEvaluator.OpeningStatus openingStatusAcrossDay(PoiResponse poi, LocalDate date) {
        OpeningHoursEvaluator.OpeningStatus best = OpeningHoursEvaluator.OpeningStatus.UNKNOWN;
        for (int hour : new int[]{11, 14, 16}) {
            OpeningHoursEvaluator.OpeningStatus status = openingStatus(poi, date, hour);
            if (status == OpeningHoursEvaluator.OpeningStatus.OPEN) {
                return OpeningHoursEvaluator.OpeningStatus.OPEN;
            }
            if (status == OpeningHoursEvaluator.OpeningStatus.CLOSED) {
                best = OpeningHoursEvaluator.OpeningStatus.CLOSED;
            }
        }
        return best;
    }

    private OpeningHoursEvaluator.OpeningStatus openingStatus(PoiResponse poi, LocalDate date, int hour) {
        Object raw = poi.getAttributes() == null ? null : poi.getAttributes().get("opening_hours");
        if (raw == null) {
            return OpeningHoursEvaluator.OpeningStatus.UNKNOWN;
        }
        return OpeningHoursEvaluator.evaluate(String.valueOf(raw), date, hour);
    }

    private double dailyWalkBudgetKm(TripPlanRequest req) {
        double base = switch (req.getStyle().getWalking()) {
            case MINIMAL -> 3.0;
            case MODERATE -> 6.0;
            case LOTS -> 10.0;
        };
        boolean hasChildren = req.getProfile().getGroupType() == TripEnums.GroupType.FAMILY
                && (hasChildAgeRanges(req) || hasChildCount(req));
        return hasChildren ? base * 0.8 : base;
    }

    private boolean hasChildAgeRanges(TripPlanRequest req) {
        return req.getProfile().getChildAgeRanges() != null
                && !req.getProfile().getChildAgeRanges().isEmpty();
    }

    private boolean hasChildCount(TripPlanRequest req) {
        Integer count = req.getBasics().getChildrenCount();
        return count != null && count > 0;
    }

    private double dailyTravelBudgetMinutes(TripPlanRequest req) {
        return switch (req.getStyle().getPace()) {
            case RELAXED -> 60;
            case BALANCED -> 120;
            case PACKED -> 240;
        };
    }

    private int visitMinutes(PoiResponse poi) {
        String s = poi.getSubcategory() == null ? "" : poi.getSubcategory();
        return switch (s) {
            case "museum", "gallery", "library" -> 120;
            case "palace", "castle", "fort", "citadel" -> 120;
            case "monument", "memorial", "artwork", "viewpoint" -> 45;
            case "park", "garden" -> 90;
            case "zoo", "aquarium", "theme_park", "water_park" -> 180;
            case "beach", "marina" -> 150;
            case "restaurant" -> 75;
            case "cafe" -> 45;
            case "shop", "mall", "department_store", "marketplace" -> 90;
            case "nightclub", "casino", "theatre", "arts_centre", "cinema" -> 120;
            default -> 90;
        };
    }

    private boolean isIndoor(PoiResponse poi) {
        return TripWeather.isIndoor(poi);
    }

    private List<String> dayNotes(List<TripPlanResponse.ScoredPoi> items, LocalDate date,
                                  boolean rainy, boolean footMode, double maxWalkKm,
                                  TripEnums.TransportMode mode) {
        List<String> notes = new ArrayList<>();
        if (rainy) {
            notes.add("Rain expected — indoor venues prioritized today.");
        }
        boolean hasFood = items.stream().anyMatch(i -> {
            String s = i.getPoi().getSubcategory();
            return s != null && List.of("restaurant", "cafe", "food_court", "bar", "fast_food").contains(s);
        });
        if (!hasFood) {
            notes.add("Lunch break recommended around 13:00.");
        }
        boolean closedAtStart = items.stream().anyMatch(i -> Boolean.FALSE.equals(i.getOpenAtScheduledTime()));
        if (closedAtStart) {
            notes.add("Some venues may open later in the day — check times.");
        }
        return notes;
    }
}