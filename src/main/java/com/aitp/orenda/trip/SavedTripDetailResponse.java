package com.aitp.orenda.trip;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Full saved-trip detail: itinerary-level fields plus every day and stop.
 */
public record SavedTripDetailResponse(
        UUID id,
        String name,
        String destination,
        LocalDate startDate,
        LocalDate endDate,
        int tripDays,
        String summary,
        String weatherSummary,
        String narrative,
        String preferenceInsight,
        List<String> notes,
        Instant createdAt,
        Instant updatedAt,
        List<SavedTripDayResponse> days
) {

    static SavedTripDetailResponse from(SavedTrip trip) {
        List<SavedTripDayResponse> days = trip.getDays() == null
                ? List.of()
                : trip.getDays().stream().map(SavedTripDayResponse::from).toList();
        return new SavedTripDetailResponse(
                trip.getId(),
                trip.getName(),
                trip.getDestination(),
                trip.getStartDate(),
                trip.getEndDate(),
                trip.getTripDays(),
                trip.getSummary(),
                trip.getWeatherSummary(),
                trip.getNarrative(),
                trip.getPreferenceInsight(),
                trip.getNotes() == null ? List.of() : trip.getNotes(),
                trip.getCreatedAt(),
                trip.getUpdatedAt(),
                days);
    }
}