package com.aitp.orenda.trip;

import com.aitp.orenda.model.PoiResponse;
import com.aitp.orenda.repository.PoiRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Scores and ranks POIs based on the trip-planning questionnaire. The algorithm:
 * <ul>
 *   <li>Expands the search area around the accommodation location.</li>
 *   <li>Filters by interest–category mapping.</li>
 *   <li>Applies weighted scoring: interest match, completeness, proximity, budget fit,
 *       family suitability, walking tolerance, and mobility.</li>
 *   <li>Returns a ranked list with per-factor breakdown and human-readable reasons.</li>
 * </ul>
 * No external LLM calls — pure rule-based scoring that is fast, deterministic, and
 * transparent. The optional "surprise me" mode adds mild randomization.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TripRecommendationService {

    private final PoiRepository poiRepository;

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
     * Maximum POIs to score and return per request.
     */
    private static final int MAX_CANDIDATES = 200;
    private static final int DEFAULT_LIMIT = 20;

    /**
     * Main entry point: score POIs around the accommodation and return a ranked plan.
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

        // 3. Fetch candidate POIs (broad, then filter in Java for flexibility)
        List<PoiResponse> candidates = new ArrayList<>(
                poiRepository.findNearby(lat, lon, radiusKm, null, 0, MAX_CANDIDATES));
        candidates.removeIf(p -> p.getCompletenessScore() < MIN_COMPLETENESS);

        // 4. Score every candidate
        List<TripPlanResponse.ScoredPoi> scored = candidates.stream()
                .map(p -> scorePoi(p, req))
                .filter(s -> s.getScore() > 0)
                .sorted(Comparator.comparingDouble(TripPlanResponse.ScoredPoi::getScore).reversed())
                .limit(DEFAULT_LIMIT)
                .toList();

        // 5. Build day plan if requested
        List<TripPlanResponse.DayPlan> dayPlan = buildDayPlan(scored, req, tripDays, lat, lon);

        // 6. Generate summary and notes
        String summary = generateSummary(scored, req, tripDays);
        List<String> notes = generateNotes(scored, req);

        return TripPlanResponse.builder()
                .tripDays(tripDays)
                .summary(summary)
                .suggestions(scored)
                .dayPlan(dayPlan)
                .notes(notes)
                .build();
    }

    /* ────────────────── Scoring ────────────────── */

    private TripPlanResponse.ScoredPoi scorePoi(PoiResponse poi, TripPlanRequest req) {
        Map<String, Double> factors = new LinkedHashMap<>();
        List<String> reasons = new ArrayList<>();

        // a) Interest–category match (strongest signal)
        double interestScore = scoreInterestMatch(poi, req.getInterests().getSelectedInterests(), reasons);
        factors.put("interest_match", interestScore);

        // b) Completeness / data quality
        double completenessScore = poi.getCompletenessScore() / 100.0 * 15;
        factors.put("completeness", completenessScore);

        // c) Proximity (closer = better)
        double distanceKm = poi.getDistanceKm() != null ? poi.getDistanceKm() : 0;
        double proximityScore = Math.max(0, 15 * (1 - distanceKm / 20.0));
        factors.put("proximity", proximityScore);

        // d) Budget fit (price signals live in attributes if available)
        double budgetScore = scoreBudgetFit(poi, req.getStyle().getBudget(), reasons);
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

        // h) Pace preference (packed vs relaxed affects how many POIs per day)
        double paceScore = scorePace(poi, req.getStyle().getPace(), reasons);
        factors.put("pace", paceScore);

        // i) Food preference (restaurant subcategories etc.)
        double foodScore = scoreFood(poi, req.getStyle().getFood(), reasons);
        factors.put("food", foodScore);

        // j) "Surprise me" adds mild noise so repeat requests differ
        double surprise = req.getStyle().getPlanningStyle() == TripEnums.PlanningStyle.SURPRISE_ME ? randomFactor() : 0;
        factors.put("surprise", surprise);

        // Weighted total (sum of weights = 100)
        double total = interestScore * 0.35
                + completenessScore * 0.08
                + proximityScore * 0.10
                + budgetScore * 0.10
                + familyScore * 0.08
                + walkingScore * 0.07
                + mobilityScore * 0.07
                + paceScore * 0.08
                + foodScore * 0.05
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

    /* ────────────────── Individual scoring dimensions ────────────────── */

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
        // Note: OSM rarely has explicit price tags; we infer from type
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

    private List<TripPlanResponse.DayPlan> buildDayPlan(List<TripPlanResponse.ScoredPoi> scored,
                                                        TripPlanRequest req, int days,
                                                        double lat, double lon) {
        if (req.getStyle().getPlanningStyle() == TripEnums.PlanningStyle.RECOMMENDATIONS_ONLY) {
            return Collections.emptyList();
        }

        // Distribute POIs across days based on pace
        int poisPerDay = switch (req.getStyle().getPace()) {
            case RELAXED -> 2;
            case BALANCED -> 3;
            case PACKED -> 5;
        };

        List<TripPlanResponse.DayPlan> plan = new ArrayList<>();
        int idx = 0;
        LocalDate start = req.getBasics().getStartDate();

        for (int d = 1; d <= days && idx < scored.size(); d++) {
            List<TripPlanResponse.ScoredPoi> dayItems = new ArrayList<>();
            int limit = Math.min(poisPerDay, scored.size() - idx);
            for (int i = 0; i < limit; i++) {
                dayItems.add(scored.get(idx++));
            }
            plan.add(TripPlanResponse.DayPlan.builder()
                    .day(d)
                    .date(start.plusDays(d - 1).toString())
                    .items(dayItems)
                    .build());
        }
        return plan;
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

    private List<String> generateNotes(List<TripPlanResponse.ScoredPoi> scored, TripPlanRequest req) {
        List<String> notes = new ArrayList<>();

        if (scored.size() < 5) {
            notes.add("Few results — consider expanding interests or travel radius.");
        }

        TripEnums.MobilityLimitation mobility = req.getProfile().getMobilityLimitation();
        if (mobility != TripEnums.MobilityLimitation.NONE) {
            notes.add("Mobility needs considered in ranking; verify accessibility on-site.");
        }

        if (req.getStyle().getPlanningStyle() == TripEnums.PlanningStyle.SURPRISE_ME) {
            notes.add("Surprise mode active — results include a randomized element.");
        }

        return notes;
    }
}