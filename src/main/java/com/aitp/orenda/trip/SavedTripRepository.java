package com.aitp.orenda.trip;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Repository
@RequiredArgsConstructor
public class SavedTripRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper jackson;

    private static final String TRIP_COLUMNS = """
            id, user_id, name, destination, start_date, end_date, trip_days,
            summary, weather_summary, narrative, preference_insight, notes,
            archived, created_at, updated_at
            """;

    // ── Inserts ────────────────────────────────────────────────────────────

    @Transactional
    public SavedTrip insertTrip(SavedTrip trip) {
        Map<String, Object> row = jdbc.queryForMap("""
                        INSERT INTO saved_trip (user_id, name, destination, start_date, end_date,
                                                 trip_days, summary, weather_summary, narrative,
                                                 preference_insight, notes)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                        RETURNING id, created_at, updated_at
                        """,
                trip.getUserId(),
                trip.getName(),
                trip.getDestination(),
                trip.getStartDate(),
                trip.getEndDate(),
                trip.getTripDays(),
                trip.getSummary(),
                trip.getWeatherSummary(),
                trip.getNarrative(),
                trip.getPreferenceInsight(),
                toJson(trip.getNotes()));
        trip.setId(UUID.fromString(row.get("id").toString()));
        trip.setCreatedAt(toInstant(row.get("created_at")));
        trip.setUpdatedAt(toInstant(row.get("updated_at")));
        return trip;
    }

    @Transactional
    public UUID insertDay(SavedTripDay day, UUID tripId) {
        Map<String, Object> row = jdbc.queryForMap("""
                        INSERT INTO saved_trip_day (trip_id, day, date, weather, narrative, notes)
                        VALUES (?, ?, ?, ?, ?, ?::jsonb)
                        RETURNING id
                        """,
                tripId, day.getDay(), day.getDate(), day.getWeather(), day.getNarrative(),
                toJson(day.getNotes()));
        return UUID.fromString(row.get("id").toString());
    }

    @Transactional
    public void insertStop(SavedTripStop stop, UUID dayId) {
        jdbc.update("""
                        INSERT INTO saved_trip_stop (day_id, poi_id, name_tr, name_en, category,
                                                     subcategory, lat, lon, score, travel_minutes,
                                                     visit_minutes, start_time, end_time,
                                                     open_at_scheduled_time, reasons, factors, sort_order)
                        VALUES (?, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                        """,
                dayId,
                stop.getPoiId(),
                stop.getNameTr(),
                stop.getNameEn(),
                stop.getCategory(),
                stop.getSubcategory(),
                stop.getLat(),
                stop.getLon(),
                stop.getScore(),
                stop.getTravelMinutes(),
                stop.getVisitMinutes(),
                stop.getStartTime(),
                stop.getEndTime(),
                stop.getOpenAtScheduledTime(),
                toJson(stop.getReasons()),
                toJson(stop.getFactors()),
                stop.getSortOrder());
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public List<SavedTrip> listForUser(UUID userId) {
        return jdbc.query("SELECT " + TRIP_COLUMNS
                        + " FROM saved_trip WHERE user_id = ? AND archived = FALSE ORDER BY updated_at DESC",
                (rs, rowNum) -> mapTrip(rs, false), userId);
    }

    public Optional<SavedTrip> findByIdForUser(UUID tripId, UUID userId) {
        List<SavedTrip> trips = jdbc.query("SELECT " + TRIP_COLUMNS
                        + " FROM saved_trip WHERE id = ? AND user_id = ?",
                (rs, rowNum) -> mapTrip(rs, true), tripId, userId);
        if (trips.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(loadDays(trips.get(0)));
    }

    private SavedTrip loadDays(SavedTrip trip) {
        List<SavedTripDay> days = new ArrayList<>();
        List<Map<String, Object>> dayRows = jdbc.queryForList("""
                SELECT id, day, date, weather, narrative, notes
                FROM saved_trip_day WHERE trip_id = ? ORDER BY day
                """, trip.getId());
        for (Map<String, Object> dayRow : dayRows) {
            UUID dayId = UUID.fromString(dayRow.get("id").toString());
            List<SavedTripStop> stops = jdbc.query("""
                    SELECT id, day_id, poi_id, name_tr, name_en, category, subcategory, lat, lon,
                           score, travel_minutes, visit_minutes, start_time, end_time,
                           open_at_scheduled_time, reasons, factors, sort_order
                    FROM saved_trip_stop WHERE day_id = ? ORDER BY sort_order
                    """, this::mapStop, dayId);
            days.add(SavedTripDay.builder()
                    .id(dayId)
                    .tripId(trip.getId())
                    .day(((Number) dayRow.get("day")).intValue())
                    .date(dayRow.get("date") == null ? null : LocalDate.parse(dayRow.get("date").toString()))
                    .weather((String) dayRow.get("weather"))
                    .narrative((String) dayRow.get("narrative"))
                    .notes(parseStringList(dayRow.get("notes")))
                    .stops(stops)
                    .build());
        }
        trip.setDays(days);
        return trip;
    }

    private SavedTripStop mapStop(ResultSet rs, int rowNum) throws SQLException {
        return SavedTripStop.builder()
                .id(UUID.fromString(rs.getString("id")))
                .dayId(UUID.fromString(rs.getString("day_id")))
                .poiId(rs.getString("poi_id"))
                .nameTr(rs.getString("name_tr"))
                .nameEn(rs.getString("name_en"))
                .category(rs.getString("category"))
                .subcategory(rs.getString("subcategory"))
                .lat(rs.getObject("lat") == null ? null : rs.getDouble("lat"))
                .lon(rs.getObject("lon") == null ? null : rs.getDouble("lon"))
                .score(rs.getObject("score") == null ? null : rs.getDouble("score"))
                .travelMinutes(rs.getObject("travel_minutes") == null ? null : rs.getInt("travel_minutes"))
                .visitMinutes(rs.getObject("visit_minutes") == null ? null : rs.getInt("visit_minutes"))
                .startTime(rs.getString("start_time"))
                .endTime(rs.getString("end_time"))
                .openAtScheduledTime(rs.getObject("open_at_scheduled_time") == null ? null : rs.getBoolean("open_at_scheduled_time"))
                .reasons(parseStringList(rs.getObject("reasons")))
                .factors(parseNumberMap(rs.getObject("factors")))
                .sortOrder(rs.getInt("sort_order"))
                .build();
    }

    private SavedTrip mapTrip(ResultSet rs, boolean includeDays) throws SQLException {
        return SavedTrip.builder()
                .id(UUID.fromString(rs.getString("id")))
                .userId(UUID.fromString(rs.getString("user_id")))
                .name(rs.getString("name"))
                .destination(rs.getString("destination"))
                .startDate(rs.getObject("start_date", LocalDate.class))
                .endDate(rs.getObject("end_date", LocalDate.class))
                .tripDays(rs.getInt("trip_days"))
                .summary(rs.getString("summary"))
                .weatherSummary(rs.getString("weather_summary"))
                .narrative(rs.getString("narrative"))
                .preferenceInsight(rs.getString("preference_insight"))
                .notes(parseStringList(rs.getObject("notes")))
                .archived(rs.getBoolean("archived"))
                .createdAt(toInstant(rs.getObject("created_at", OffsetDateTime.class)))
                .updatedAt(toInstant(rs.getObject("updated_at", OffsetDateTime.class)))
                .build();
    }

    // ── Updates ────────────────────────────────────────────────────────────

    @Transactional
    public void updateDetails(UUID tripId, String name, String destination,
                              LocalDate startDate, LocalDate endDate, int tripDays,
                              String summary, String weatherSummary, String narrative,
                              String preferenceInsight, List<String> notes) {
        jdbc.update("""
                        UPDATE saved_trip
                        SET name = ?, destination = ?, start_date = ?, end_date = ?,
                            trip_days = ?, summary = ?, weather_summary = ?, narrative = ?,
                            preference_insight = ?, notes = ?::jsonb, updated_at = NOW()
                        WHERE id = ?
                        """,
                name, destination, startDate, endDate, tripDays,
                summary, weatherSummary, narrative, preferenceInsight,
                toJson(notes), tripId);
    }

    @Transactional
    public void archiveTrip(UUID tripId, UUID userId) {
        jdbc.update("""
                        UPDATE saved_trip SET archived = TRUE, updated_at = NOW()
                        WHERE id = ? AND user_id = ?
                        """,
                tripId, userId);
    }

    @Transactional
    public void deleteTrip(UUID tripId, UUID userId) {
        jdbc.update("DELETE FROM saved_trip WHERE id = ? AND user_id = ?", tripId, userId);
    }

    /**
     * Replaces the day/stop plan of a trip (used by PUT and recalculate).
     * Days and stops are deleted and re-inserted so ordering is authoritative.
     */
    @Transactional
    public void replacePlan(UUID tripId, List<SavedTripDay> days) {
        jdbc.update("DELETE FROM saved_trip_day WHERE trip_id = ?", tripId);
        if (days == null) {
            return;
        }
        for (SavedTripDay day : days) {
            UUID dayId = insertDay(day, tripId);
            if (day.getStops() != null) {
                int order = 0;
                for (SavedTripStop stop : day.getStops()) {
                    stop.setSortOrder(order++);
                    insertStop(stop, dayId);
                }
            }
        }
    }

    // ── JSON helpers ───────────────────────────────────────────────────────

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return jackson.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize to JSON: {}", e.getMessage());
            return "[]";
        }
    }

    private List<String> parseStringList(Object json) {
        if (json == null) return List.of();
        try {
            return jackson.readValue(json.toString(), new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Double> parseNumberMap(Object json) {
        if (json == null) return Map.of();
        try {
            return jackson.readValue(json.toString(), new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof OffsetDateTime odt) return odt.toInstant();
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        return null;
    }
}