package com.aitp.orenda.trip;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One itinerary day of a saved trip.
 */
@Data
@Builder
public class SavedTripDay {

    private UUID id;
    private UUID tripId;
    private int day;
    private LocalDate date;
    private String weather;
    private String narrative;
    private List<String> notes;
    private List<SavedTripStop> stops;
}