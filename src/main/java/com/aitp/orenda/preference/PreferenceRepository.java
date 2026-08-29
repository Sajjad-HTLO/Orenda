package com.aitp.orenda.preference;

import com.aitp.orenda.trip.TripEnums;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * JDBC persistence for the traveler profile, learned preference weights and the
 * raw feedback events behind them.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class PreferenceRepository {

    private static final double DEFAULT_WEIGHT = 0.5;

    /**
     * How long a learned trip constraint (budget cap, radius cap, flags) stays
     * in effect without being refreshed by new feedback. After this window the
     * constraint stops applying.
     */
    private static final int CONSTRAINT_TTL_DAYS = 30;

    private final JdbcTemplate jdbc;

    // ── Profile ─────────────────────────────────────────────────────────────

    @Transactional
    public void upsertProfile(TravelerProfileRequest req) {
        jdbc.update("""
                        INSERT INTO traveler_profile
                            (session_id, interests, pace, budget, walking, food, diet,
                             group_type, age_range, mobility, traveler_count, children_count)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (session_id) DO UPDATE SET
                            interests   = EXCLUDED.interests,
                            pace        = EXCLUDED.pace,
                            budget      = EXCLUDED.budget,
                            walking     = EXCLUDED.walking,
                            food        = EXCLUDED.food,
                            diet        = EXCLUDED.diet,
                            group_type  = EXCLUDED.group_type,
                            age_range   = EXCLUDED.age_range,
                            mobility    = EXCLUDED.mobility,
                            traveler_count = EXCLUDED.traveler_count,
                            children_count = EXCLUDED.children_count,
                            updated_at  = NOW()
                        """,
                (PreparedStatement ps) -> {
                    ps.setString(1, req.getSessionId());
                    ps.setArray(2, ps.getConnection().createArrayOf("text", interestNames(req.getInterests())));
                    ps.setString(3, enumName(req.getPace()));
                    ps.setString(4, enumName(req.getBudget()));
                    ps.setString(5, enumName(req.getWalking()));
                    ps.setString(6, enumName(req.getFood()));
                    ps.setString(7, enumName(req.getDiet()));
                    ps.setString(8, enumName(req.getGroupType()));
                    ps.setString(9, enumName(req.getAgeRange()));
                    ps.setString(10, enumName(req.getMobility()));
                    ps.setObject(11, req.getTravelerCount());
                    ps.setObject(12, req.getChildrenCount());
                });
    }

    public TravelerProfileResponse findProfile(String sessionId) {
        return jdbc.query("""
                        SELECT session_id, interests, pace, budget, walking, food, diet,
                               group_type, age_range, mobility, traveler_count, children_count, updated_at
                        FROM traveler_profile
                        WHERE session_id = ?
                        """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    return TravelerProfileResponse.builder()
                            .sessionId(rs.getString("session_id"))
                            .interests(parseInterests(rs.getArray("interests")))
                            .pace(enumValue(TripEnums.Pace.class, rs.getString("pace")))
                            .budget(enumValue(TripEnums.Budget.class, rs.getString("budget")))
                            .walking(enumValue(TripEnums.WalkingLevel.class, rs.getString("walking")))
                            .food(enumValue(TripEnums.FoodPreference.class, rs.getString("food")))
                            .diet(enumValue(TripEnums.Diet.class, rs.getString("diet")))
                            .groupType(enumValue(TripEnums.GroupType.class, rs.getString("group_type")))
                            .ageRange(enumValue(TripEnums.AgeRange.class, rs.getString("age_range")))
                            .mobility(enumValue(TripEnums.MobilityLimitation.class, rs.getString("mobility")))
                            .travelerCount(rs.getObject("traveler_count", Integer.class))
                            .childrenCount(rs.getObject("children_count", Integer.class))
                            .updatedAt(rs.getObject("updated_at", OffsetDateTime.class))
                            .build();
                },
                sessionId);
    }

    public List<TripEnums.Interest> loadProfileInterests(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Collections.emptyList();
        }
        List<String> names = jdbc.query("""
                        SELECT interests FROM traveler_profile WHERE session_id = ?
                        """,
                rs -> {
                    if (!rs.next()) {
                        return Collections.<String>emptyList();
                    }
                    java.sql.Array array = rs.getArray("interests");
                    if (array == null) {
                        return Collections.emptyList();
                    }
                    Object[] raw = (Object[]) array.getArray();
                    List<String> out = new ArrayList<>(raw.length);
                    for (Object o : raw) {
                        out.add(String.valueOf(o));
                    }
                    return out;
                },
                sessionId);
        return names.stream()
                .map(name -> enumValue(TripEnums.Interest.class, name))
                .filter(Objects::nonNull)
                .toList();
    }

    // ── Learned weights ─────────────────────────────────────────────────────

    /**
     * All stored preference weights for a session (empty when none exist yet).
     */
    public Map<String, Double> loadWeights(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Collections.emptyMap();
        }
        return jdbc.query("""
                        SELECT category, weight FROM user_preference WHERE session_id = ?
                        """,
                rs -> {
                    Map<String, Double> out = new LinkedHashMap<>();
                    while (rs.next()) {
                        out.put(rs.getString("category"), rs.getDouble("weight"));
                    }
                    return out;
                },
                sessionId);
    }

    /**
     * Single-category weight, defaulting to 0.5 when nothing learned yet.
     */
    public double loadWeight(String sessionId, String category) {
        if (sessionId == null || sessionId.isBlank()) {
            return DEFAULT_WEIGHT;
        }
        Double w = jdbc.query("""
                        SELECT weight FROM user_preference WHERE session_id = ? AND category = ?
                        """,
                rs -> rs.next() ? rs.getDouble(1) : null,
                sessionId, category);
        return w == null ? DEFAULT_WEIGHT : w;
    }

    /**
     * Persists one feedback event and applies the newly computed weight to the
     * session's category in the same transaction.
     */
    @Transactional
    public void recordFeedback(String sessionId, String poiId, PreferenceReaction reaction,
                               Integer rating, FeedbackReason reason, String category, double newWeight) {
        jdbc.update("""
                        INSERT INTO preference_feedback (session_id, poi_id, reaction, rating, reason)
                        VALUES (?, ?::uuid, ?, ?, ?)
                        """,
                sessionId, poiId, reaction.name(), rating, reason == null ? null : reason.name());

        jdbc.update("""
                        INSERT INTO user_preference (session_id, category, weight, feedback_count, last_seen)
                        VALUES (?, ?, ?, 1, NOW())
                        ON CONFLICT (session_id, category) DO UPDATE SET
                            weight = ?, feedback_count = user_preference.feedback_count + 1, last_seen = NOW()
                        """,
                sessionId, category, newWeight, newWeight);
    }

    // ── Constraints (from feedback reasons) ─────────────────────────────────

    public void upsertConstraint(String sessionId, String constraintKey, String value) {
        jdbc.update("""
                        INSERT INTO preference_constraint (session_id, constraint_key, value, last_seen)
                        VALUES (?, ?, ?, NOW())
                        ON CONFLICT (session_id, constraint_key) DO UPDATE SET
                            value = EXCLUDED.value, last_seen = NOW()
                        """,
                sessionId, constraintKey, value);
    }

    public Map<String, String> loadConstraints(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Collections.emptyMap();
        }
        return jdbc.query("""
                        SELECT constraint_key, value FROM preference_constraint
                        WHERE session_id = ? AND last_seen > NOW() - INTERVAL '%d days'
                        """.formatted(CONSTRAINT_TTL_DAYS),
                rs -> {
                    Map<String, String> out = new LinkedHashMap<>();
                    while (rs.next()) {
                        out.put(rs.getString("constraint_key"), rs.getString("value"));
                    }
                    return out;
                },
                sessionId);
    }

    public String loadConstraintValue(String sessionId, String constraintKey) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return jdbc.query("""
                        SELECT value FROM preference_constraint
                        WHERE session_id = ? AND constraint_key = ?
                          AND last_seen > NOW() - INTERVAL '%d days'
                        """.formatted(CONSTRAINT_TTL_DAYS),
                rs -> rs.next() ? rs.getString(1) : null,
                sessionId, constraintKey);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static String[] interestNames(List<TripEnums.Interest> interests) {
        if (interests == null) {
            return new String[0];
        }
        return interests.stream().map(Enum::name).toArray(String[]::new);
    }

    private static List<TripEnums.Interest> parseInterests(java.sql.Array array) {
        if (array == null) {
            return Collections.emptyList();
        }
        try {
            Object[] raw = (Object[]) array.getArray();
            List<TripEnums.Interest> out = new ArrayList<>(raw.length);
            for (Object o : raw) {
                TripEnums.Interest interest = enumValue(TripEnums.Interest.class, String.valueOf(o));
                if (interest != null) {
                    out.add(interest);
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("Failed to parse traveler interests: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private static String enumName(Enum<?> e) {
        return e == null ? null : e.name();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}