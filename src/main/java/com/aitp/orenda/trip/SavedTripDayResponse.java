package com.aitp.orenda.trip;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One itinerary day within a saved-trip detail response.
 */
public record SavedTripDayResponse(
        UUID id,
        int day,
        LocalDate date,
        String weather,
        String narrative,
        List<String> notes,
        List<SavedTripStopResponse> stops
) {

    static SavedTripDayResponse from(SavedTripDay day) {
        List<SavedTripStopResponse> stops = day.getStops() == null
                ? List.of()
                : day.getStops().stream().map(SavedTripStopResponse::from).toList();
        return new SavedTripDayResponse(
                day.getId(),
                day.getDay(),
                day.getDate(),
                day.getWeather(),
                day.getNarrative(),
                day.getNotes() == null ? List.of() : day.getNotes(),
                stops);
    }
}