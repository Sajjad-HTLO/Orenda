package com.aitp.orenda.trip;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Body for POST /api/trips/recalculate — a real-time event that adjusts the
 * remaining stops of a saved itinerary.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RecalculateTripRequest {

    public enum Event {
        /** Rain forecast: indoor stops are pulled earlier, open-roof ones pushed back. */
        RAIN,
        /** The traveler is running late: times shift by {@code delayMinutes}. */
        DELAY,
        /** A road is closed / a stop is unreachable: it is dropped and the day re-timed. */
        ROAD_CLOSURE
    }

    @NotNull(message = "tripId must not be null")
    private UUID tripId;

    @NotNull(message = "event must not be null")
    private Event event;

    /**
     * Day from which the recalculation applies. When null the whole trip is
     * affected.
     */
    private LocalDate eventDate;

    /** Required for {@link Event#DELAY} — minutes to shift the remaining times by. */
    @Min(value = 1, message = "delayMinutes must be positive")
    private Integer delayMinutes;

    /** Required for {@link Event#ROAD_CLOSURE} — the saved stop id that is unreachable. */
    private UUID affectedStopId;

    private String note;
}