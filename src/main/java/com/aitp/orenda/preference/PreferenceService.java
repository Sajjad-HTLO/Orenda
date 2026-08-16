package com.aitp.orenda.preference;

import com.aitp.orenda.model.PoiResponse;
import com.aitp.orenda.repository.PoiRepository;
import com.aitp.orenda.trip.TripEnums;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Preference learning: converts immediate reactions (like / dislike / love /
 * not interested / rated) plus optional reasons into per-category weights
 * (0..1) for a traveler, and surfaces a natural-language insight about them.
 * <p>
 * Weights are updated with an exponential moving average so recent feedback
 * counts more than old feedback, and a single strong signal never swings a
 * category to an extreme.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PreferenceService {

    private static final double ALPHA = 0.2;
    private static final double DEFAULT_WEIGHT = 0.5;
    private static final int SIMILAR_RADIUS_KM = 3;
    private static final int SIMILAR_LIMIT = 5;

    private final PreferenceRepository preferenceRepository;
    private final PoiRepository poiRepository;

    /**
     * Processes one preference-feedback event: persists it, nudges the session's
     * weight for the POI's preference category, and returns updated weights plus
     * an insight message. For {@code FIND_SIMILAR} it also returns same-category
     * POIs near the reported one.
     */
    @Transactional
    public PreferenceFeedbackResponse processFeedback(PreferenceFeedbackRequest req) {
        PoiResponse poi = poiRepository.findById(req.getPoiId()).orElse(null);
        PreferenceCategory category = poi == null ? PreferenceCategory.OTHER : PreferenceCategory.forPoi(poi);

        if (poi != null && category != PreferenceCategory.OTHER) {
            double old = preferenceRepository.loadWeight(req.getSessionId(), category.name());
            double newWeight = clamp01(old + ALPHA * (targetFor(req) - old));
            preferenceRepository.recordFeedback(req.getSessionId(), req.getPoiId(), req.getReaction(),
                    req.getRating(), req.getReason(), category.name(), newWeight);
        } else {
            preferenceRepository.recordFeedback(req.getSessionId(), req.getPoiId(), req.getReaction(),
                    req.getRating(), req.getReason(), PreferenceCategory.OTHER.name(), DEFAULT_WEIGHT);
        }
        applyReasonConstraint(req.getSessionId(), req.getReason());

        Map<String, Double> weights = overviewWeights(req.getSessionId());

        return PreferenceFeedbackResponse.builder()
                .accepted(true)
                .message(messageFor(req))
                .updatedWeights(weights)
                .insight(insightFor(weights))
                .similarPois(req.getReason() == FeedbackReason.FIND_SIMILAR && poi != null
                        ? findSimilar(poi) : null)
                .build();
    }

    /**
     * All weights for a session, including untouched categories at the default,
     * ordered highest first.
     */
    public Map<String, Double> overviewWeights(String sessionId) {
        Map<String, Double> weights = new LinkedHashMap<>();
        for (PreferenceCategory category : PreferenceCategory.values()) {
            weights.put(category.name(), preferenceRepository.loadWeight(sessionId, category.name()));
        }
        return weights.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), LinkedHashMap::putAll);
    }

    /**
     * Stored weights only (categories that have feedback); empty for a new session.
     */
    public Map<String, Double> loadWeights(String sessionId) {
        return preferenceRepository.loadWeights(sessionId);
    }

    /**
     * Long-term baseline interests from onboarding (empty when none set).
     */
    public List<com.aitp.orenda.trip.TripEnums.Interest> loadProfileInterests(String sessionId) {
        return preferenceRepository.loadProfileInterests(sessionId);
    }

    /**
     * Trip-shaping constraints derived from feedback reasons. Never throws; a
     * session with no constraints yields {@link TripConstraints#NONE}.
     */
    public TripConstraints loadConstraints(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return TripConstraints.NONE;
        }
        Map<String, String> rows = preferenceRepository.loadConstraints(sessionId);
        if (rows.isEmpty()) {
            return TripConstraints.NONE;
        }
        return new TripConstraints(
                parseBudget(rows.get("BUDGET_CAP")),
                parseDouble(rows.get("MAX_RADIUS_KM")),
                "true".equals(rows.get("AVOID_CROWDED")),
                "true".equals(rows.get("FAMILY_SAFE")),
                "true".equals(rows.get("QUIET")));
    }

    private void applyReasonConstraint(String sessionId, FeedbackReason reason) {
        if (sessionId == null || reason == null) {
            return;
        }
        switch (reason) {
            case TOO_EXPENSIVE -> {
                String current = preferenceRepository.loadConstraintValue(sessionId, "BUDGET_CAP");
                TripEnums.Budget cap = lowerBudget(parseBudget(current, TripEnums.Budget.LUXURY));
                preferenceRepository.upsertConstraint(sessionId, "BUDGET_CAP", cap.name());
            }
            case TOO_FAR -> {
                String current = preferenceRepository.loadConstraintValue(sessionId, "MAX_RADIUS_KM");
                double radius = parseDouble(current) == null ? 8.0 : Math.max(3.0, parseDouble(current) * 0.7);
                preferenceRepository.upsertConstraint(sessionId, "MAX_RADIUS_KM", String.valueOf(radius));
            }
            case TOO_CROWDED -> preferenceRepository.upsertConstraint(sessionId, "AVOID_CROWDED", "true");
            case NOT_SUITABLE_FOR_KIDS -> preferenceRepository.upsertConstraint(sessionId, "FAMILY_SAFE", "true");
            case PREFER_QUIETER -> preferenceRepository.upsertConstraint(sessionId, "QUIET", "true");
            case FIND_SIMILAR -> { /* no trip constraint */ }
        }
    }

    private static TripEnums.Budget lowerBudget(TripEnums.Budget current) {
        return switch (current) {
            case BUDGET -> TripEnums.Budget.BUDGET;
            case MID_RANGE -> TripEnums.Budget.BUDGET;
            case PREMIUM -> TripEnums.Budget.MID_RANGE;
            case LUXURY -> TripEnums.Budget.PREMIUM;
        };
    }

    private static TripEnums.Budget parseBudget(String value) {
        return parseBudget(value, null);
    }

    private static TripEnums.Budget parseBudget(String value, TripEnums.Budget fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return TripEnums.Budget.valueOf(value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static Double parseDouble(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public TravelerProfileResponse upsertProfile(TravelerProfileRequest req) {
        preferenceRepository.upsertProfile(req);
        return preferenceRepository.findProfile(req.getSessionId());
    }

    public TravelerProfileResponse getProfile(String sessionId) {
        return preferenceRepository.findProfile(sessionId);
    }

    /**
     * e.g. "I noticed you tend to prefer cultural experiences and local food.
     * You seem to skip nightlife and shopping. I've adjusted your
     * recommendations accordingly." Null when nothing meaningful learned yet.
     */
    public String insightFor(Map<String, Double> weights) {
        if (weights == null || weights.isEmpty()) {
            return null;
        }
        List<String> prefers = new ArrayList<>();
        List<String> avoids = new ArrayList<>();
        for (PreferenceCategory category : PreferenceCategory.values()) {
            Double value = weights.get(category.name());
            if (value == null) {
                continue;
            }
            if (value >= 0.7) {
                prefers.add(category.label());
            } else if (value <= 0.3) {
                avoids.add(category.label());
            }
        }
        if (prefers.isEmpty() && avoids.isEmpty()) {
            return null;
        }
        StringBuilder message = new StringBuilder();
        if (!prefers.isEmpty()) {
            message.append("I noticed you tend to prefer ").append(humanJoin(prefers)).append('.');
        }
        if (!avoids.isEmpty()) {
            if (message.length() > 0) {
                message.append(' ');
            }
            message.append("You seem to skip ").append(humanJoin(avoids)).append('.');
        }
        message.append(" I've adjusted your recommendations accordingly.");
        return message.toString();
    }

    private List<PoiResponse> findSimilar(PoiResponse poi) {
        try {
            return poiRepository.findNearby(poi.getLat(), poi.getLon(), SIMILAR_RADIUS_KM,
                    poi.getCategory(), 0, SIMILAR_LIMIT);
        } catch (Exception e) {
            log.warn("Failed to find similar POIs for {}: {}", poi.getId(), e.getMessage());
            return List.of();
        }
    }

    private double targetFor(PreferenceFeedbackRequest req) {
        double signal = switch (req.getReaction()) {
            case LOVE -> 1.0;
            case LIKE -> 0.7;
            case DISLIKE -> -0.7;
            case NOT_INTERESTED -> -1.0;
            case RATED -> req.getRating() == null ? 0.0 : (req.getRating() - 3) / 2.0;
        };
        if (req.getReason() != null && req.getReason() != FeedbackReason.FIND_SIMILAR) {
            signal -= 0.3;
        }
        return (signal + 1.0) / 2.0; // map -1..1 → 0..1
    }

    private String messageFor(PreferenceFeedbackRequest req) {
        return switch (req.getReaction()) {
            case LOVE -> "Noted — adding more places like this to your recommendations.";
            case LIKE -> "Got it — this counts toward your preferences.";
            case DISLIKE -> "Understood — this type will rank lower for you.";
            case NOT_INTERESTED -> "Removed — this won't be suggested again.";
            case RATED -> "Thanks for the rating!";
        };
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String humanJoin(List<String> items) {
        if (items.size() <= 1) {
            return items.get(0);
        }
        if (items.size() == 2) {
            return items.get(0) + " and " + items.get(1);
        }
        return String.join(", ", items.subList(0, items.size() - 1))
                + ", and " + items.get(items.size() - 1);
    }
}