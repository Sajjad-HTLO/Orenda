package com.aitp.orenda.trip;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A scheduled stop inside a saved itinerary day. POI fields are snapshotted so
 * the saved plan survives later changes to (or removal of) the underlying POI.
 */
@Data
@Builder
public class SavedTripStop {

    private UUID id;
    private UUID dayId;
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
    private int sortOrder;
}