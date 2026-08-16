package com.aitp.orenda.trip;

import com.aitp.orenda.model.PoiResponse;
import com.aitp.orenda.preference.PreferenceCategory;
import com.aitp.orenda.preference.PreferenceService;
import com.aitp.orenda.preference.TripConstraints;
import com.aitp.orenda.repository.PoiRepository;
import com.aitp.orenda.weather.WeatherResponse;
import com.aitp.orenda.weather.WeatherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * End-to-end trip planner implementing the full pipeline:
 * <pre>
 * Candidate POIs → Filter → Score → Opening hours → Weather → Travel time
 *   → Walking constraints → Budget → Optimization → AI-generated itinerary
 * </pre>
 * <ul>
 *   <li><b>Candidates/Filter/Score</b> — POIs around the accommodation, filtered by
 *       data quality and closed-on-every-trip-day, then weighted scoring over the
 *       interest, completeness, proximity, budget, family, walking, mobility, pace
 *       and food dimensions.</li>
 *   <li><b>Opening hours</b> — venues closed for the whole trip are excluded;
 *       open venues are rewarded.</li>
 *   <li><b>Weather</b> — rainy trips boost indoor venues, clear trips boost
 *       outdoor ones.</li>
 *   <li><b>Travel time</b> — real routing time from the base is scored, so "close
 *       in kilometres but slow to reach" POIs are penalised.</li>
 *   <li><b>Walking constraints / Budget / Optimization</b> — handed to
 *       {@link ItineraryOptimizer}, which enforces daily walking and travel budgets,
 *       ordering and times.</li>
 * </ul>
 * No external LLM is used — the itinerary is deterministic and transparent, with
 * human-readable reasons per POI and per day.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TripRecommendationService {

    private final PoiRepository poiRepository;
    private final WeatherService weatherService;
    private final TravelTimeEstimator travelTimeEstimator;
    private final ItineraryOptimizer itineraryOptimizer;
    private final PreferenceService preferenceService;
    private final ItineraryNarrator itineraryNarrator;

    /**
     * Default search radius (km) around the accommodation. Can be expanded if needed.
     */
    private static final double DEFAULT_RADIUS_KM = 15.0;
    private static final double MAX_RADIUS_KM = 50.0;

    /**
     * Minimum completeness score to consider a POI (avoids very sparse entries).
     */
    private static final int MIN_COMPLETENESS = 20;

    /**
     * Maximum POIs to fetch and score per request.
     */
    private static final int MAX_CANDIDATES = 200;

    /**
     * Top POIs that get real routing travel-time computed (cheap for a handful).
     */
    private static final int ROUTED_CANDIDATES = 25;

    /**
     * Maximum POIs returned in the final suggestion list.
     */
    private static final int DEFAULT_LIMIT = 20;

    /**
     * Main entry point: runs the full planning pipeline and returns the ranked plan.
     */
    public TripPlanResponse recommend(TripPlanRequest req) {
        // 1. Determine search centre and radius
        double[] centre = parseLatLon(req.getBasics().getAccommodationLocation());
        double lat = centre[0];
        double lon = centre[1];
        double radiusKm = adjustRadius(req.getBasics().getTransportMode());

        // 2. Determine number of days
        long days = ChronoUnit.DAYS.between(req.getBasics().getStartDate(), req.getBasics().getEndDate()) + 1;
        int tripDays = Math.max(1, (int) days);
        LocalDate startDate = req.getBasics().getStartDate();

        // 3. Weather context (graceful: empty when forecast unavailable)
        TripWeather weather = buildWeather(lat, lon, tripDays);

        // 3b. Learned preferences + long-term profile + feedback constraints
        String sessionId = req.getSessionId();
        Map<String, Double> prefWeights = preferenceService.loadWeights(sessionId);
        TripConstraints constraints = preferenceService.loadConstraints(sessionId);
        List<TripEnums.Interest> interests = resolveInterests(req, sessionId);

        // Constraints shape the search before scoring:
        // "too far" shrinks the radius; "too expensive" caps the budget.
        radiusKm = effectiveRadius(radiusKm, constraints);
        TripEnums.Budget effectiveBudget = effectiveBudget(req.getStyle().getBudget(), constraints);

        // 4. Candidate POIs → filter → score (cheap dimensions, incl. opening hours + weather)
        List<PoiResponse> candidates = new ArrayList<>(
                poiRepository.findNearby(lat, lon, radiusKm, null, 0, MAX_CANDIDATES));
        candidates.removeIf(p -> p.getCompletenessScore() < MIN_COMPLETENESS);

        List<TripPlanResponse.ScoredPoi> scored = candidates.stream()
                .map(p -> scorePoi(p, req, weather, startDate, tripDays, interests, prefWeights,
                        constraints, effectiveBudget))
                .filter(s -> s.getScore() > 0)
                .sorted(Comparator.comparingDouble(TripPlanResponse.ScoredPoi::getScore).reversed())
                .limit(ROUTED_CANDIDATES)
                .toList();

        // 5. Travel-time stage for the top candidates (from the base location)
        List<TripPlanResponse.ScoredPoi> withTravel = scored.stream()
                .map(s -> applyTravelTime(s, req, lat, lon))
                .sorted(Comparator.comparingDouble(TripPlanResponse.ScoredPoi::getScore).reversed())
                .limit(DEFAULT_LIMIT)
                .toList();

        // 6. Optimization → ordered, timed day-by-day itinerary
        List<TripPlanResponse.DayPlan> dayPlan = itineraryOptimizer.build(
                withTravel, req, tripDays, lat, lon, weather);

        // 7. Summary and notes
        String summary = generateSummary(withTravel, req, tripDays);
        List<String> notes = generateNotes(withTravel, req, weather, tripDays, constraints);
        String weatherSummary = generateWeatherSummary(weather, startDate, tripDays);
        String preferenceInsight = preferenceService.insightFor(prefWeights);

        // 8. AI-generated itinerary narrative (natural-language reasoning layer)
        ItineraryNarrator.NarrativeOutput narrative = itineraryNarrator.narrate(req, dayPlan, withTravel,
                preferenceInsight, weatherSummary, constraints, effectiveBudget, radiusKm);
        List<TripPlanResponse.DayPlan> narratedPlan = applyDayNarratives(dayPlan, narrative.dayNarratives());

        return TripPlanResponse.builder()
                .tripDays(tripDays)
                .summary(summary)
                .suggestions(withTravel)
                .dayPlan(narratedPlan)
                .notes(notes)
                .weatherSummary(weatherSummary)
                .preferenceInsight(preferenceInsight)
                .narrative(narrative.overall())
                .build();
    }

    private List<TripPlanResponse.DayPlan> applyDayNarratives(List<TripPlanResponse.DayPlan> dayPlan,
                                                              List<String> dayNarratives) {
        if (dayPlan.isEmpty()) {
            return dayPlan;
        }
        List<TripPlanResponse.DayPlan> narrated = new ArrayList<>(dayPlan.size());
        for (int i = 0; i < dayPlan.size(); i++) {
            TripPlanResponse.DayPlan day = dayPlan.get(i);
            narrated.add(TripPlanResponse.DayPlan.builder()
                    .day(day.getDay())
                    .date(day.getDate())
                    .weather(day.getWeather())
                    .items(day.getItems())
                    .notes(day.getNotes())
                    .narrative(i < dayNarratives.size() ? dayNarratives.get(i) : null)
                    .build());
        }
        return narrated;
    }

    /* ────────────────── Weather context ────────────────── */

    private TripWeather buildWeather(double lat, double lon, int tripDays) {
        if (tripDays <= 0) {
            return TripWeather.empty();
        }
        try {
            WeatherResponse resp = weatherService.getWeather(lat, lon, Math.min(tripDays, 16));
            if (resp == null || resp.daily() == null || resp.daily().isEmpty()) {
                return TripWeather.empty();
            }
            return new TripWeather(resp.daily());
        } catch (Exception e) {
            log.warn("Weather forecast unavailable; proceeding without weather adjustments. error={}",
                    e.getMessage());
            return TripWeather.empty();
        }
    }

    private String generateWeatherSummary(TripWeather weather, LocalDate startDate, int tripDays) {
        if (weather == null || weather.days() == null || weather.days().isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < tripDays; i++) {
            String date = startDate.plusDays(i).toString();
            if (weather.byDate(date).isPresent()) {
                parts.add(startDate.plusDays(i).getMonth().name().substring(0, 3) + " "
                        + startDate.plusDays(i).getDayOfMonth() + ": " + weather.description(date));
            }
        }
        return String.join(" · ", parts);
    }

    /* ────────────────── Scoring ────────────────── */

    private TripPlanResponse.ScoredPoi scorePoi(PoiResponse poi, TripPlanRequest req,
                                                TripWeather weather, LocalDate startDate, int tripDays,
                                                List<TripEnums.Interest> interests,
                                                Map<String, Double> prefWeights,
                                                TripConstraints constraints,
                                                TripEnums.Budget effectiveBudget) {
        Map<String, Double> factors = new LinkedHashMap<>();
        List<String> reasons = new ArrayList<>();

        // a) Interest–category match (strongest signal)
        double interestScore = scoreInterestMatch(poi, interests, reasons);
        factors.put("interest_match", interestScore);

        // b) Completeness / data quality
        double completenessScore = poi.getCompletenessScore() / 100.0 * 15;
        factors.put("completeness", completenessScore);

        // c) Proximity (closer = better)
        double distanceKm = poi.getDistanceKm() != null ? poi.getDistanceKm() : 0;
        double proximityScore = Math.max(0, 15 * (1 - distanceKm / 20.0));
        factors.put("proximity", proximityScore);

        // d) Budget fit (price signals live in attributes if available)
        double budgetScore = scoreBudgetFit(poi, effectiveBudget, reasons);
        factors.put("budget_fit", budgetScore);

        // e) Family suitability
        double familyScore = scoreFamilySuitability(poi, req.getProfile(), reasons);
        factors.put("family_suitability", familyScore);

        // f) Walking tolerance
        double walkingScore = scoreWalkingTolerance(poi, req.getStyle().getWalking(), req.getProfile().getMobilityLimitation(), reasons);
        factors.put("walking_tolerance", walkingScore);

        // g) Mobility fit
        double mobilityScore = scoreMobility(poi, req.getProfile().getMobilityLimitation(), reasons);
        factors.put("mobility", mobilityScore);

        // h) Pace preference
        double paceScore = scorePace(poi, req.getStyle().getPace(), reasons);
        factors.put("pace", paceScore);

        // i) Food preference
        double foodScore = scoreFood(poi, req.getStyle().getFood(), reasons);
        factors.put("food", foodScore);

        // j) Opening hours stage — closed for the whole trip means filtered out
        boolean closedAllTrip = closedAllTrip(poi, startDate, tripDays);
        if (closedAllTrip) {
            reasons.add("Closed on all of your trip dates");
            return TripPlanResponse.ScoredPoi.builder()
                    .poi(poi)
                    .score(0)
                    .factors(factors)
                    .reasons(reasons)
                    .build();
        }
        double openingScore = openDuringTrip(poi, startDate, tripDays, reasons);
        factors.put("opening_hours", openingScore);

        // k) Weather stage — indoor on rainy trips, outdoor on clear trips
        double weatherScore = scoreWeather(poi, weather, startDate, tripDays, reasons);
        factors.put("weather", weatherScore);

        // l) Learned preference weights (from feedback) — boosts what this
        //    traveler demonstrably enjoys, suppresses what they avoid
        double preferenceScore = scoreLearnedPreference(poi, prefWeights, reasons);
        factors.put("preference", preferenceScore);

        // m) Feedback-reason constraints — "not suitable for kids" excludes
        //    adult-oriented venues outright; crowd/quiet/budget shape the score
        if (constraints.familySafe() && isAdultOriented(poi)) {
            reasons.add("Excluded — adult-oriented venue");
            return TripPlanResponse.ScoredPoi.builder()
                    .poi(poi)
                    .score(0)
                    .factors(factors)
                    .reasons(reasons)
                    .build();
        }
        double constraintScore = scoreConstraints(poi, constraints, reasons);
        factors.put("constraints", constraintScore);

        // n) "Surprise me" adds mild noise so repeat requests differ
        double surprise = req.getStyle().getPlanningStyle() == TripEnums.PlanningStyle.SURPRISE_ME ? randomFactor() : 0;
        factors.put("surprise", surprise);

        // Weighted total (sum of weights = 100)
        double total = interestScore * 0.27
                + completenessScore * 0.07
                + proximityScore * 0.08
                + budgetScore * 0.10
                + familyScore * 0.07
                + walkingScore * 0.07
                + mobilityScore * 0.06
                + paceScore * 0.07
                + foodScore * 0.05
                + openingScore * 0.06
                + weatherScore * 0.06
                + preferenceScore * 0.06
                + constraintScore * 0.05
                + surprise;

        if (total <= 5) { // noise floor
            return TripPlanResponse.ScoredPoi.builder()
                    .poi(poi)
                    .score(0)
                    .factors(factors)
                    .reasons(Collections.emptyList())
                    .build();
        }

        return TripPlanResponse.ScoredPoi.builder()
                .poi(poi)
                .score(Math.round(total * 100.0) / 100.0)
                .factors(factors)
                .reasons(reasons)
                .build();
    }

    /**
     * Travel-time stage: computes the real routed time from the base to the POI
     * and folds it into the score as an extra factor (max 10 points).
     */
    private TripPlanResponse.ScoredPoi applyTravelTime(TripPlanResponse.ScoredPoi scored,
                                                       TripPlanRequest req, double baseLat, double baseLon) {
        TripEnums.TransportMode mode = req.getBasics().getTransportMode();
        double travelMinutes = travelTimeEstimator.minutesBetween(
                baseLat, baseLon, scored.getPoi().getLat(), scored.getPoi().getLon(), mode);
        double travelFactor = Math.max(0, 10 - travelMinutes / 8.0);

        Map<String, Double> factors = new LinkedHashMap<>(scored.getFactors());
        factors.put("travel_time", Math.round(travelFactor * 100.0) / 100.0);

        List<String> reasons = new ArrayList<>(scored.getReasons());
        reasons.add("%d min from your base".formatted(Math.round(travelMinutes)));

        return TripPlanResponse.ScoredPoi.builder()
                .poi(scored.getPoi())
                .score(Math.round((scored.getScore() + travelFactor) * 100.0) / 100.0)
                .factors(factors)
                .reasons(reasons)
                .build();
    }

    /* ────────────────── Opening hours scoring ────────────────── */

    private OpeningHoursEvaluator.OpeningStatus statusOnDate(PoiResponse poi, LocalDate date) {
        Object raw = poi.getAttributes() == null ? null : poi.getAttributes().get("opening_hours");
        if (raw == null) {
            return OpeningHoursEvaluator.OpeningStatus.UNKNOWN;
        }
        return OpeningHoursEvaluator.evaluate(String.valueOf(raw), date, 14);
    }

    private boolean closedAllTrip(PoiResponse poi, LocalDate startDate, int tripDays) {
        boolean anyOpen = false;
        boolean anyClosed = false;
        for (int i = 0; i < tripDays; i++) {
            OpeningHoursEvaluator.OpeningStatus status = statusOnDate(poi, startDate.plusDays(i));
            if (status == OpeningHoursEvaluator.OpeningStatus.OPEN) {
                anyOpen = true;
            } else if (status == OpeningHoursEvaluator.OpeningStatus.CLOSED) {
                anyClosed = true;
            }
        }
        return anyClosed && !anyOpen; // all known days closed; all-unknown → keep
    }

    private double openDuringTrip(PoiResponse poi, LocalDate startDate, int tripDays, List<String> reasons) {
        boolean anyOpen = false;
        for (int i = 0; i < tripDays; i++) {
            if (statusOnDate(poi, startDate.plusDays(i)) == OpeningHoursEvaluator.OpeningStatus.OPEN) {
                anyOpen = true;
                break;
            }
        }
        if (anyOpen) {
            reasons.add("Open during your trip");
            return 10;
        }
        return 5; // opening hours unknown → neutral
    }

    /* ────────────────── Weather scoring ────────────────── */

    private double scoreWeather(PoiResponse poi, TripWeather weather, LocalDate startDate,
                                int tripDays, List<String> reasons) {
        if (weather == null || weather.days() == null || weather.days().isEmpty()) {
            return 5;
        }
        int rainyDays = 0;
        int clearDays = 0;
        for (int i = 0; i < tripDays; i++) {
            String date = startDate.plusDays(i).toString();
            if (weather.byDate(date).isEmpty()) {
                continue; // no forecast for this day — stay neutral
            }
            if (weather.isRainy(date)) {
                rainyDays++;
            }
            if (weather.isOutdoorGood(date)) {
                clearDays++;
            }
        }
        boolean indoor = TripWeather.isIndoor(poi);
        boolean outdoor = TripWeather.isOutdoor(poi);
        double rainyFraction = tripDays == 0 ? 0 : (double) rainyDays / tripDays;

        if (rainyFraction >= 0.4) {
            if (indoor) {
                reasons.add("Mostly indoor — well suited to the rainy forecast");
                return 10;
            }
            if (outdoor) {
                reasons.add("Outdoor venue during rainy days — consider a backup plan");
                return 2;
            }
            return 5;
        }
        if (clearDays > rainyDays) {
            if (outdoor) {
                reasons.add("Clear conditions during your trip — great for outdoor spots");
                return 10;
            }
            return 5;
        }
        return 5;
    }

    /* ────────────────── Learned preference scoring ────────────────── */

    /**
     * Scores a POI against the traveler's learned preference weights. Unknown
     * categories and sessions with no feedback stay at a neutral 0.5.
     */
    private double scoreLearnedPreference(PoiResponse poi, Map<String, Double> prefWeights,
                                          List<String> reasons) {
        if (prefWeights == null || prefWeights.isEmpty()) {
            return 5;
        }
        PreferenceCategory category = PreferenceCategory.forPoi(poi);
        if (category == PreferenceCategory.OTHER) {
            return 5;
        }
        Double weight = prefWeights.get(category.name());
        if (weight == null) {
            return 5;
        }
        if (weight >= 0.8) {
            reasons.add("Matches your learned love of " + category.label());
        } else if (weight <= 0.2) {
            reasons.add("Usually not your style");
        }
        return 10 * (0.3 + 0.7 * weight); // 3..10
    }

    /**
     * Uses the trip's selected interests; when none are given (or the list is
     * empty), falls back to the traveler's long-term onboarding interests.
     */
    private List<TripEnums.Interest> resolveInterests(TripPlanRequest req, String sessionId) {
        List<TripEnums.Interest> requested = req.getInterests().getSelectedInterests();
        if (requested != null && !requested.isEmpty()) {
            return requested;
        }
        if (sessionId == null || sessionId.isBlank()) {
            return requested == null ? List.of() : requested;
        }
        try {
            List<TripEnums.Interest> profile = preferenceService.loadProfileInterests(sessionId);
            return profile == null ? List.of() : profile;
        } catch (Exception e) {
            log.warn("Failed to load traveler profile interests for session {}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    /* ────────────────── Feedback-reason constraint scoring ────────────────── */

    /**
     * Scores a POI against learned feedback constraints. Starts neutral (10) and
     * adjusts for crowd, quiet and family preferences.
     */
    private double scoreConstraints(PoiResponse poi, TripConstraints constraints, List<String> reasons) {
        double score = 10;
        if (constraints.avoidCrowded() && isPopular(poi)) {
            score -= 6;
            reasons.add("Popular and often crowded — de-prioritized for you");
        }
        if (constraints.quiet()) {
            if (isLoud(poi)) {
                score -= 6;
                reasons.add("Lively venue — you've said you prefer quieter places");
            } else if (isQuiet(poi)) {
                score += 3;
                reasons.add("Quiet spot — matches your preference");
            }
        }
        return Math.max(0, score);
    }

    private boolean isAdultOriented(PoiResponse poi) {
        String sub = poi.getSubcategory() == null ? "" : poi.getSubcategory();
        if (List.of("nightclub", "casino", "bar", "pub", "strip_club").contains(sub)) {
            return true;
        }
        Object adultsOnly = poi.getAttributes() == null ? null : poi.getAttributes().get("adults_only");
        if ("yes".equals(adultsOnly)) {
            return true;
        }
        Object minAge = poi.getAttributes() == null ? null : poi.getAttributes().get("min_age");
        if (minAge != null) {
            try {
                return Integer.parseInt(String.valueOf(minAge)) >= 18;
            } catch (NumberFormatException ignored) {
                // not a number — ignore
            }
        }
        return false;
    }

    /**
     * Popularity proxy: Tripadvisor review counts live in attributes
     * ({@code review_count}) when the crawler has enriched the POI.
     */
    private boolean isPopular(PoiResponse poi) {
        Object count = poi.getAttributes() == null ? null : poi.getAttributes().get("review_count");
        if (count == null) {
            return false;
        }
        try {
            return Double.parseDouble(String.valueOf(count)) > 2000;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isLoud(PoiResponse poi) {
        String sub = poi.getSubcategory() == null ? "" : poi.getSubcategory();
        String cat = poi.getCategory() == null ? "" : poi.getCategory();
        return "entertainment".equals(cat)
                || List.of("nightclub", "casino", "bar", "pub", "theme_park", "water_park", "stadium", "sports_centre").contains(sub);
    }

    private boolean isQuiet(PoiResponse poi) {
        String sub = poi.getSubcategory() == null ? "" : poi.getSubcategory();
        return List.of("park", "garden", "nature_reserve", "viewpoint", "beach", "cafe", "library", "memorial").contains(sub);
    }

    /**
     * "Too far" feedback caps the search radius.
     */
    private double effectiveRadius(double baseRadiusKm, TripConstraints constraints) {
        if (constraints.maxRadiusKm() == null) {
            return baseRadiusKm;
        }
        return Math.min(baseRadiusKm, constraints.maxRadiusKm());
    }

    /**
     * "Too expensive" feedback caps the effective budget used in scoring.
     */
    private TripEnums.Budget effectiveBudget(TripEnums.Budget requested, TripConstraints constraints) {
        if (constraints.budgetCap() == null) {
            return requested;
        }
        int requestedRank = budgetRank(requested);
        int capRank = budgetRank(constraints.budgetCap());
        return requestedRank <= capRank ? requested : constraints.budgetCap();
    }

    private static int budgetRank(TripEnums.Budget budget) {
        return switch (budget) {
            case BUDGET -> 0;
            case MID_RANGE -> 1;
            case PREMIUM -> 2;
            case LUXURY -> 3;
        };
    }

    /* ────────────────── Individual scoring dimensions (unchanged) ────────────────── */

    private double scoreInterestMatch(PoiResponse poi, List<TripEnums.Interest> interests, List<String> reasons) {
        if (interests == null || interests.isEmpty()) return 25; // neutral baseline

        String category = poi.getCategory() != null ? poi.getCategory().toLowerCase() : "";
        String subcategory = poi.getSubcategory() != null ? poi.getSubcategory().toLowerCase() : "";
        String combined = category + " " + subcategory;

        // Map interests to category keywords
        Map<TripEnums.Interest, List<String>> interestKeywords = Map.ofEntries(
                Map.entry(TripEnums.Interest.HISTORY, List.of("historic", "monument", "castle", "ruins", "archaeological", "palace", "fort", "citadel")),
                Map.entry(TripEnums.Interest.MUSEUMS, List.of("museum", "gallery", "exhibition", "arts_centre", "library")),
                Map.entry(TripEnums.Interest.NATURE, List.of("nature", "park", "garden", "nature_reserve", "peak", "waterfall", "cave", "hot_spring", "spring", "volcano", "beach")),
                Map.entry(TripEnums.Interest.BEACHES, List.of("beach", "beach_resort", "marina", "swimming_pool", "water_park")),
                Map.entry(TripEnums.Interest.FOOD, List.of("restaurant", "cafe", "bar", "pub", "fast_food", "food_court", "ice_cream", "marketplace")),
                Map.entry(TripEnums.Interest.SHOPPING, List.of("shop", "marketplace", "mall", "department_store")),
                Map.entry(TripEnums.Interest.NIGHTLIFE, List.of("nightclub", "bar", "pub", "casino", "entertainment")),
                Map.entry(TripEnums.Interest.PHOTOGRAPHY, List.of("viewpoint", "peak", "waterfall", "monument", "palace", "bridge", "architecture")),
                Map.entry(TripEnums.Interest.ARCHITECTURE, List.of("architecture", "building", "church", "mosque", "palace", "castle", "monument", "historic")),
                Map.entry(TripEnums.Interest.ADVENTURE, List.of("adventure", "theme_park", "water_park", "sports_centre", "peak", "cave")),
                Map.entry(TripEnums.Interest.LOCAL_CULTURE, List.of("marketplace", "place_of_worship", "festival", "culture", "arts_centre", "theatre")),
                Map.entry(TripEnums.Interest.LUXURY, List.of("luxury", "spa", "hotel", "resort", "fine_dining")),
                Map.entry(TripEnums.Interest.HIDDEN_GEMS, List.of("hidden", "off_the_beaten_path", "secret", "lesser_known")),
                Map.entry(TripEnums.Interest.FAMILY_ACTIVITIES, List.of("park", "zoo", "aquarium", "theme_park", "water_park", "playground", "family", "children"))
        );

        int matches = 0;
        for (TripEnums.Interest interest : interests) {
            List<String> keywords = interestKeywords.get(interest);
            if (keywords != null && keywords.stream().anyMatch(k -> combined.contains(k))) {
                matches++;
                reasons.add("Matches your interest in " + interest.name().toLowerCase().replace('_', ' '));
            }
        }

        // Also check attributes for tags like "diet:vegetarian", "tourism=attraction" etc.
        if (poi.getAttributes() != null) {
            for (TripEnums.Interest interest : interests) {
                if (checkAttributesForInterest(poi.getAttributes(), interest)) {
                    matches++;
                    if (matches > interests.size()) matches = interests.size(); // cap
                }
            }
        }

        // Score: max 25, proportional to how many selected interests match
        return Math.min(25, (matches * 25.0) / Math.max(1, interests.size()));
    }

    private boolean checkAttributesForInterest(Map<String, Object> attrs, TripEnums.Interest interest) {
        // Look at OSM tags in attributes JSONB
        return switch (interest) {
            case FOOD -> attrs.containsKey("cuisine") || attrs.containsKey("diet:vegetarian");
            case HISTORY -> attrs.containsKey("historic") || attrs.containsKey("wikidata");
            case MUSEUMS -> attrs.containsKey("tourism") && "museum".equals(attrs.get("tourism"));
            case NATURE, BEACHES -> attrs.containsKey("natural") || attrs.containsKey("leisure");
            case NIGHTLIFE -> attrs.containsKey("nightclub") || attrs.containsKey("bar");
            case PHOTOGRAPHY -> attrs.containsKey("viewpoint") || attrs.containsKey("wikidata");
            case LOCAL_CULTURE -> attrs.containsKey("craft") || attrs.containsKey("workshop");
            default -> false;
        };
    }

    private double scoreBudgetFit(PoiResponse poi, TripEnums.Budget budget, List<String> reasons) {
        // Check attributes for price signals
        Map<String, Object> attrs = poi.getAttributes();
        if (attrs == null) return 10; // neutral

        // Look for price level indicators in OSM tags
        String category = poi.getCategory();
        String subcategory = poi.getSubcategory();

        return switch (budget) {
            case BUDGET -> {
                boolean affordable = "fast_food".equals(subcategory) || "street_vendor".equals(subcategory)
                        || "cafe".equals(subcategory) || "marketplace".equals(subcategory)
                        || "hostel".equals(subcategory) || "camp_site".equals(subcategory);
                if (affordable) reasons.add("Budget-friendly option");
                yield affordable ? 15 : 5;
            }
            case MID_RANGE -> {
                boolean mid = "restaurant".equals(subcategory) || "hotel".equals(subcategory)
                        || "guest_house".equals(subcategory) || "apartment".equals(subcategory);
                if (mid) reasons.add("Good value for money");
                yield mid ? 15 : 10;
            }
            case PREMIUM -> {
                boolean premium = "spa".equals(subcategory) || "resort".equals(subcategory)
                        || "chalet".equals(subcategory);
                if (premium) reasons.add("Premium experience");
                yield premium ? 15 : 10;
            }
            case LUXURY -> {
                boolean luxury = "luxury".equals(subcategory) || "fine_dining".equals(attrs.get("cuisine"))
                        || "5_star".equals(attrs.get("stars"));
                if (luxury) reasons.add("Luxury option");
                yield luxury ? 15 : 5;
            }
        };
    }

    private double scoreFamilySuitability(PoiResponse poi, TripPlanRequest.TravelerProfile profile, List<String> reasons) {
        if (profile.getGroupType() != TripEnums.GroupType.FAMILY) return 10; // neutral

        String category = poi.getCategory();
        String subcategory = poi.getSubcategory();

        boolean familyFriendly = "park".equals(subcategory) || "zoo".equals(subcategory)
                || "aquarium".equals(subcategory) || "theme_park".equals(subcategory)
                || "water_park".equals(subcategory) || "playground".equals(subcategory)
                || "beach".equals(subcategory) || "museum".equals(subcategory)
                || "castle".equals(subcategory) || "farm".equals(subcategory);

        // Check for child-unfriendly tags
        Map<String, Object> attrs = poi.getAttributes();
        boolean adultsOnly = attrs != null
                && ("yes".equals(attrs.get("adults_only")) || "18+".equals(attrs.get("min_age")));

        if (familyFriendly) reasons.add("Great for families with children");
        if (adultsOnly) reasons.add("Note: may not be suitable for children");

        if (adultsOnly) return 0;
        return familyFriendly ? 15 : 8;
    }

    private double scoreWalkingTolerance(PoiResponse poi, TripEnums.WalkingLevel walking, TripEnums.MobilityLimitation mobility, List<String> reasons) {
        // We don't have exact walking distances per POI, so we infer from category
        String category = poi.getCategory();
        String subcategory = poi.getSubcategory();

        boolean lotsOfWalking = "park".equals(subcategory) || "nature_reserve".equals(subcategory)
                || "peak".equals(subcategory) || "hiking".equals(subcategory)
                || "theme_park".equals(subcategory) || "zoo".equals(subcategory)
                || "garden".equals(subcategory);

        boolean minimalWalking = "viewpoint".equals(subcategory) || "monument".equals(subcategory)
                || "memorial".equals(subcategory) || "cafe".equals(subcategory)
                || "restaurant".equals(subcategory) || "museum".equals(subcategory)
                || "gallery".equals(subcategory);

        return switch (walking) {
            case MINIMAL -> {
                if (minimalWalking) {
                    reasons.add("Minimal walking required");
                    yield 15;
                }
                if (lotsOfWalking) {
                    reasons.add("Involves significant walking");
                    yield 3;
                }
                yield 10;
            }
            case MODERATE -> {
                if (lotsOfWalking) {
                    reasons.add("Moderate walking involved");
                    yield 12;
                }
                yield 10;
            }
            case LOTS -> {
                if (lotsOfWalking) {
                    reasons.add("Perfect for lots of walking");
                    yield 15;
                }
                yield 10;
            }
        };
    }

    private double scoreMobility(PoiResponse poi, TripEnums.MobilityLimitation mobility, List<String> reasons) {
        if (mobility == TripEnums.MobilityLimitation.NONE) return 10;

        Map<String, Object> attrs = poi.getAttributes();
        if (attrs == null) return 5; // unknown

        boolean wheelchair = "yes".equals(attrs.get("wheelchair"));
        boolean wheelchairLimited = "limited".equals(attrs.get("wheelchair"));
        boolean stroller = "yes".equals(attrs.get("stroller"));

        return switch (mobility) {
            case WHEELCHAIR -> {
                if (wheelchair) {
                    reasons.add("Wheelchair accessible");
                    yield 15;
                }
                if (wheelchairLimited) {
                    reasons.add("Limited wheelchair access");
                    yield 8;
                }
                reasons.add("Wheelchair accessibility unknown");
                yield 5;
            }
            case STROLLER -> {
                if (stroller) {
                    reasons.add("Stroller friendly");
                    yield 15;
                }
                if (wheelchair) {
                    reasons.add("Likely stroller accessible");
                    yield 12;
                }
                reasons.add("Stroller accessibility unknown");
                yield 5;
            }
            case LIMITED_WALKING -> {
                if (wheelchair || stroller) {
                    reasons.add("Good for limited walking");
                    yield 12;
                }
                yield 8;
            }
            default -> 10;
        };
    }

    private double scorePace(PoiResponse poi, TripEnums.Pace pace, List<String> reasons) {
        // Pace mainly affects day-plan density; here we just nudge
        String subcategory = poi.getSubcategory();
        boolean quickVisit = "viewpoint".equals(subcategory) || "monument".equals(subcategory)
                || "memorial".equals(subcategory) || "artwork".equals(subcategory);
        boolean longVisit = "museum".equals(subcategory) || "park".equals(subcategory)
                || "theme_park".equals(subcategory) || "zoo".equals(subcategory)
                || "spa".equals(subcategory);

        return switch (pace) {
            case RELAXED -> {
                if (longVisit) {
                    reasons.add("Worth a relaxed visit");
                    yield 12;
                }
                if (quickVisit) {
                    reasons.add("Quick stop, fits relaxed pace");
                    yield 10;
                }
                yield 8;
            }
            case BALANCED -> 10;
            case PACKED -> {
                if (quickVisit) {
                    reasons.add("Quick stop, fits packed schedule");
                    yield 12;
                }
                yield 8;
            }
        };
    }

    private double scoreFood(PoiResponse poi, TripEnums.FoodPreference food, List<String> reasons) {
        Map<String, Object> attrs = poi.getAttributes();
        if (attrs == null) return 5;

        String cuisine = (String) attrs.get("cuisine");
        boolean veg = "yes".equals(attrs.get("diet:vegetarian"));
        boolean vegan = "yes".equals(attrs.get("diet:vegan"));
        String type = (String) attrs.get("amenity"); // restaurant, cafe, fast_food, etc.

        return switch (food) {
            case LOCAL -> {
                boolean local = cuisine != null && !cuisine.isBlank() && !"international".equalsIgnoreCase(cuisine);
                if (local) reasons.add("Serves local cuisine");
                yield local ? 10 : 5;
            }
            case FINE_DINING -> {
                boolean fine = "restaurant".equals(type) && (cuisine != null && (cuisine.contains("fine") || cuisine.contains("gourmet")));
                if (fine) reasons.add("Fine dining option");
                yield fine ? 10 : 5;
            }
            case STREET_FOOD -> {
                boolean street = "fast_food".equals(type) || "food_court".equals(type) || "street_vendor".equals(type);
                if (street) reasons.add("Street food style");
                yield street ? 10 : 5;
            }
            case VEGETARIAN -> {
                if (veg) {
                    reasons.add("Vegetarian-friendly");
                    yield 10;
                }
                yield 5;
            }
            case VEGAN -> {
                if (vegan) {
                    reasons.add("Vegan-friendly");
                    yield 10;
                }
                yield 5;
            }
            case NO_PREFERENCE -> 5;
        };
    }

    /* ────────────────── Helpers ────────────────── */

    private double randomFactor() {
        return (Math.random() - 0.5) * 2; // -1 to +1
    }

    private double[] parseLatLon(String location) {
        // Expected format: "lat,lon" or "lat, lon"
        if (location == null || !location.contains(",")) {
            // Default: central Istanbul
            return new double[]{41.0082, 28.9784};
        }
        String[] parts = location.split(",");
        try {
            double lat = Double.parseDouble(parts[0].trim());
            double lon = Double.parseDouble(parts[1].trim());
            return new double[]{lat, lon};
        } catch (NumberFormatException e) {
            log.warn("Invalid location format '{}', using Istanbul centre", location);
            return new double[]{41.0082, 28.9784};
        }
    }

    private double adjustRadius(TripEnums.TransportMode mode) {
        // Walking/biking = smaller radius, driving = larger
        return switch (mode) {
            case FOOT -> 5.0;
            case BIKE -> 10.0;
            case TRANSIT -> 15.0;
            case TAXI -> 20.0;
            case DRIVING -> DEFAULT_RADIUS_KM;
        };
    }

    private String generateSummary(List<TripPlanResponse.ScoredPoi> scored, TripPlanRequest req, int days) {
        if (scored.isEmpty()) {
            return "No matching POIs found — try expanding your interests or search area.";
        }
        int count = scored.size();
        String topCategory = scored.get(0).getPoi().getCategory();
        String topName = scored.get(0).getPoi().getNameTr();

        return String.format(
                "Found %d suggestions for your %d-day %s trip to %s. Top match: %s (%s).",
                count, days, req.getProfile().getGroupType().name().toLowerCase(),
                req.getBasics().getDestination(), topName, topCategory);
    }

    private List<String> generateNotes(List<TripPlanResponse.ScoredPoi> scored, TripPlanRequest req,
                                       TripWeather weather, int tripDays, TripConstraints constraints) {
        List<String> notes = new ArrayList<>();

        if (scored.size() < 5) {
            notes.add("Few results — consider expanding interests or travel radius.");
        }

        if (constraints.budgetCap() != null) {
            notes.add("Budget capped at " + constraints.budgetCap().name().toLowerCase().replace('_', ' ')
                    + " based on your \"too expensive\" feedback.");
        }
        if (constraints.maxRadiusKm() != null) {
            notes.add("Search kept within " + Math.round(constraints.maxRadiusKm() * 10.0) / 10.0
                    + " km of your base — you prefer places close by.");
        }
        if (constraints.avoidCrowded()) {
            notes.add("Popular, crowded venues are de-prioritized for you.");
        }
        if (constraints.familySafe()) {
            notes.add("Adult-oriented venues excluded based on your feedback.");
        }
        if (constraints.quiet()) {
            notes.add("Quieter spots are favored based on your feedback.");
        }

        TripEnums.MobilityLimitation mobility = req.getProfile().getMobilityLimitation();
        if (mobility != TripEnums.MobilityLimitation.NONE) {
            notes.add("Mobility needs considered in ranking; verify accessibility on-site.");
        }

        if (req.getStyle().getPlanningStyle() == TripEnums.PlanningStyle.SURPRISE_ME) {
            notes.add("Surprise mode active — results include a randomized element.");
        }

        if (weather != null && weather.days() != null && !weather.days().isEmpty()) {
            int rainy = 0;
            for (int i = 0; i < tripDays; i++) {
                if (weather.isRainy(req.getBasics().getStartDate().plusDays(i).toString())) {
                    rainy++;
                }
            }
            if (rainy > 0) {
                notes.add("Rain expected on %d of your %d days — indoor venues are boosted.".formatted(
                        rainy, tripDays));
            }
        }

        return notes;
    }
}