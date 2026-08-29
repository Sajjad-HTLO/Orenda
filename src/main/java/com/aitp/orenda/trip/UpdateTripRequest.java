package com.aitp.orenda.trip;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Body for PUT/PATCH /api/trips/{id}. Replaces the itinerary wholesale:
 * the day list (and each day's stop order) is authoritative, so reordering is
 * just re-ordering the {@code stops} arrays.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UpdateTripRequest {

    private String name;
    private String destination;
    private LocalDate startDate;
    private LocalDate endDate;
    private List<String> notes;

    @Valid
    private List<Day> days;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Day {
        private int day;
        private LocalDate date;
        private String weather;
        private String narrative;
        private List<String> notes;

        @Valid
        private List<Stop> stops;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Stop {
        private UUID id;
        private String poiId;
        private String nameTr;
        private String nameEn;
        private String category;
        private String subcategory;
        private Double lat;
        private Double lon;
        private Double score;
        private Integer travelMinutes;
        private Integer visitMinutes;
        private String startTime;
        private String endTime;
        private Boolean openAtScheduledTime;
        private List<String> reasons;
        private Map<String, Double> factors;
    }
}