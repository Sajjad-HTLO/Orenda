package com.aitp.orenda.trip;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight row for trip listings (no day/stop detail).
 */
public record SavedTripSummaryResponse(
        UUID id,
        String name,
        String destination,
        String startDate,
        String endDate,
        int tripDays,
        String summary,
        String weatherSummary,
        Instant updatedAt
) {

    static SavedTripSummaryResponse from(SavedTrip trip) {
        return new SavedTripSummaryResponse(
                trip.getId(),
                trip.getName(),
                trip.getDestination(),
                trip.getStartDate() == null ? null : trip.getStartDate().toString(),
                trip.getEndDate() == null ? null : trip.getEndDate().toString(),
                trip.getTripDays(),
                trip.getSummary(),
                trip.getWeatherSummary(),
                trip.getUpdatedAt());
    }
}