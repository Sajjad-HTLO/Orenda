package com.aitp.orenda.trip;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A user's saved trip: itinerary-level snapshot of a {@link TripPlanResponse}.
 */
@Data
@Builder
public class SavedTrip {

    private UUID id;
    private UUID userId;
    private String name;
    private String destination;
    private LocalDate startDate;
    private LocalDate endDate;
    private int tripDays;
    private String summary;
    private String weatherSummary;
    private String narrative;
    private String preferenceInsight;
    private List<String> notes;
    private boolean archived;
    private Instant createdAt;
    private Instant updatedAt;
    private List<SavedTripDay> days;
}