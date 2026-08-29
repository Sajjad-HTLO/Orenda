package com.aitp.orenda.trip;

import com.aitp.orenda.model.PoiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Recalculates the remaining stops of a saved itinerary given a real-time
 * event (rain, delay, road closure). Works entirely on the saved snapshot so
 * it stays fast and offline-safe; the result is then persisted by
 * {@link SavedTripService}.
 */
@Service
public class TripRecalculationService {

    private static final String DEFAULT_START = "09:30";
    private static final int DEFAULT_VISIT_MINUTES = 90;
    private static final int DEFAULT_TRAVEL_MINUTES = 15;

    public SavedTrip apply(SavedTrip trip, RecalculateTripRequest request) {
        SavedTrip adjusted = copy(trip);
        List<SavedTripDay> days = adjusted.getDays() == null ? new ArrayList<>() : new ArrayList<>(adjusted.getDays());

        switch (request.getEvent()) {
            case RAIN -> days = reorderForRain(days, request.getEventDate());
            case DELAY -> days = shiftForDelay(days, request.getEventDate(), request.getDelayMinutes());
            case ROAD_CLOSURE -> days = dropForRoadClosure(days, request.getAffectedStopId());
        }

        String note = recalcNote(request);
        List<String> notes = new ArrayList<>();
        if (adjusted.getNotes() != null) {
            notes.addAll(adjusted.getNotes());
        }
        notes.add(0, note);
        adjusted.setNotes(notes);
        adjusted.setDays(days);
        return adjusted;
    }

    // ── RAIN: pull indoor stops earlier, push open-roof / outdoor stops back ──

    private List<SavedTripDay> reorderForRain(List<SavedTripDay> days, LocalDate eventDate) {
        List<SavedTripDay> result = new ArrayList<>(days.size());
        for (SavedTripDay day : days) {
            if (!isAffected(day, eventDate) || day.getStops() == null || day.getStops().size() < 2) {
                result.add(day);
                continue;
            }
            String firstStart = day.getStops().get(0).getStartTime();
            List<SavedTripStop> sorted = new ArrayList<>(day.getStops());
            sorted.sort(Comparator.comparingInt((SavedTripStop s) -> rainPriority(s)).reversed()
                    .thenComparing(s -> s.getStartTime() == null ? "" : s.getStartTime()));
            result.add(rebuildDay(day, reTime(sorted, firstStart),
                    appendNote(day.getNotes(), "Recalculated for rain — indoor stops moved earlier, open-air stops pushed back.")));
        }
        return result;
    }

    private int rainPriority(SavedTripStop stop) {
        PoiResponse poi = minimalPoi(stop);
        if (TripWeather.isIndoor(poi)) {
            return 2;
        }
        if (TripWeather.isOpenRoof(poi)) {
            return 0;
        }
        return 1;
    }

    // ── DELAY: shift remaining times forward ──

    private List<SavedTripDay> shiftForDelay(List<SavedTripDay> days, LocalDate eventDate, Integer delayMinutes) {
        if (delayMinutes == null || delayMinutes <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "delayMinutes is required for DELAY");
        }
        List<SavedTripDay> result = new ArrayList<>(days.size());
        for (SavedTripDay day : days) {
            if (!isAffected(day, eventDate) || day.getStops() == null || day.getStops().isEmpty()) {
                result.add(day);
                continue;
            }
            List<SavedTripStop> shifted = new ArrayList<>(day.getStops().size());
            for (SavedTripStop stop : day.getStops()) {
                shifted.add(SavedTripStop.builder()
                        .id(stop.getId())
                        .dayId(stop.getDayId())
                        .poiId(stop.getPoiId())
                        .nameTr(stop.getNameTr())
                        .nameEn(stop.getNameEn())
                        .category(stop.getCategory())
                        .subcategory(stop.getSubcategory())
                        .lat(stop.getLat())
                        .lon(stop.getLon())
                        .score(stop.getScore())
                        .travelMinutes(stop.getTravelMinutes())
                        .visitMinutes(stop.getVisitMinutes())
                        .startTime(shift(stop.getStartTime(), delayMinutes))
                        .endTime(shift(stop.getEndTime(), delayMinutes))
                        .openAtScheduledTime(stop.getOpenAtScheduledTime())
                        .reasons(stop.getReasons())
                        .factors(stop.getFactors())
                        .sortOrder(stop.getSortOrder())
                        .build());
            }
            result.add(rebuildDay(day, shifted,
                    appendNote(day.getNotes(), "Times shifted " + delayMinutes + " minutes later due to a delay.")));
        }
        return result;
    }

    // ── ROAD_CLOSURE: drop the unreachable stop, re-time the rest of the day ──

    private List<SavedTripDay> dropForRoadClosure(List<SavedTripDay> days, UUID affectedStopId) {
        if (affectedStopId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "affectedStopId is required for ROAD_CLOSURE");
        }
        List<SavedTripDay> result = new ArrayList<>(days.size());
        boolean dropped = false;
        for (SavedTripDay day : days) {
            if (day.getStops() == null || day.getStops().isEmpty()) {
                result.add(day);
                continue;
            }
            List<SavedTripStop> remaining = day.getStops().stream()
                    .filter(s -> !affectedStopId.equals(s.getId()))
                    .toList();
            if (remaining.size() == day.getStops().size()) {
                result.add(day);
                continue;
            }
            dropped = true;
            String firstStart = remaining.isEmpty() ? null : remaining.get(0).getStartTime();
            String droppedName = day.getStops().stream()
                    .filter(s -> affectedStopId.equals(s.getId()))
                    .findFirst()
                    .map(SavedTripStop::getNameTr)
                    .orElse("A stop");
            result.add(rebuildDay(day, reTime(remaining, firstStart),
                    appendNote(day.getNotes(), "Recalculated — " + droppedName + " is unreachable (road closure) and was removed.")));
        }
        if (!dropped) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stop not found in this trip");
        }
        return result;
    }

    // ── Shared helpers ─────────────────────────────────────────────────────

    private boolean isAffected(SavedTripDay day, LocalDate eventDate) {
        return eventDate == null || day.getDate() == null || !day.getDate().isBefore(eventDate);
    }

    private List<SavedTripStop> reTime(List<SavedTripStop> stops, String firstStart) {
        if (stops == null || stops.isEmpty()) {
            return stops == null ? List.of() : stops;
        }
        List<SavedTripStop> result = new ArrayList<>(stops.size());
        LocalTime cursor = firstStart == null ? LocalTime.parse(DEFAULT_START) : LocalTime.parse(firstStart);
        for (SavedTripStop stop : stops) {
            int visit = stop.getVisitMinutes() == null ? DEFAULT_VISIT_MINUTES : stop.getVisitMinutes();
            LocalTime end = cursor.plusMinutes(visit);
            result.add(SavedTripStop.builder()
                    .id(stop.getId())
                    .dayId(stop.getDayId())
                    .poiId(stop.getPoiId())
                    .nameTr(stop.getNameTr())
                    .nameEn(stop.getNameEn())
                    .category(stop.getCategory())
                    .subcategory(stop.getSubcategory())
                    .lat(stop.getLat())
                    .lon(stop.getLon())
                    .score(stop.getScore())
                    .travelMinutes(stop.getTravelMinutes())
                    .visitMinutes(stop.getVisitMinutes())
                    .startTime(cursor.toString())
                    .endTime(end.toString())
                    .openAtScheduledTime(stop.getOpenAtScheduledTime())
                    .reasons(stop.getReasons())
                    .factors(stop.getFactors())
                    .sortOrder(stop.getSortOrder())
                    .build());
            int travel = stop.getTravelMinutes() == null ? DEFAULT_TRAVEL_MINUTES : stop.getTravelMinutes();
            cursor = end.plusMinutes(travel);
        }
        return result;
    }

    private SavedTripDay rebuildDay(SavedTripDay day, List<SavedTripStop> stops, List<String> notes) {
        return SavedTripDay.builder()
                .id(day.getId())
                .tripId(day.getTripId())
                .day(day.getDay())
                .date(day.getDate())
                .weather(day.getWeather())
                .narrative(day.getNarrative())
                .notes(notes)
                .stops(stops)
                .build();
    }

    private List<String> appendNote(List<String> notes, String note) {
        List<String> result = new ArrayList<>();
        if (notes != null) {
            result.addAll(notes);
        }
        result.add(note);
        return result;
    }

    private String shift(String time, int minutes) {
        if (time == null || !time.contains(":")) {
            return time;
        }
        try {
            LocalTime t = LocalTime.parse(time);
            return t.plusMinutes(minutes).toString();
        } catch (Exception e) {
            return time;
        }
    }

    private PoiResponse minimalPoi(SavedTripStop stop) {
        return PoiResponse.builder()
                .category(stop.getCategory())
                .subcategory(stop.getSubcategory())
                .nameTr(stop.getNameTr())
                .nameEn(stop.getNameEn())
                .attributes(new HashMap<>())
                .build();
    }

    private String recalcNote(RecalculateTripRequest request) {
        return switch (request.getEvent()) {
            case RAIN -> "Itinerary recalculated for rain" + (request.getEventDate() == null ? "" : " from " + request.getEventDate())
                    + (request.getNote() == null || request.getNote().isBlank() ? "" : " — " + request.getNote().trim());
            case DELAY -> "Itinerary recalculated after a delay of " + request.getDelayMinutes() + " minutes"
                    + (request.getNote() == null || request.getNote().isBlank() ? "" : " — " + request.getNote().trim());
            case ROAD_CLOSURE -> "Itinerary recalculated for a road closure"
                    + (request.getNote() == null || request.getNote().isBlank() ? "" : " — " + request.getNote().trim());
        };
    }

    private SavedTrip copy(SavedTrip trip) {
        return SavedTrip.builder()
                .id(trip.getId())
                .userId(trip.getUserId())
                .name(trip.getName())
                .destination(trip.getDestination())
                .startDate(trip.getStartDate())
                .endDate(trip.getEndDate())
                .tripDays(trip.getTripDays())
                .summary(trip.getSummary())
                .weatherSummary(trip.getWeatherSummary())
                .narrative(trip.getNarrative())
                .preferenceInsight(trip.getPreferenceInsight())
                .notes(trip.getNotes() == null ? List.of() : new ArrayList<>(trip.getNotes()))
                .archived(trip.isArchived())
                .createdAt(trip.getCreatedAt())
                .updatedAt(trip.getUpdatedAt())
                .days(trip.getDays() == null ? new ArrayList<>() : new ArrayList<>(trip.getDays()))
                .build();
    }
}