package com.aitp.orenda.trip;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A scheduled stop within a saved-trip day response.
 */
public record SavedTripStopResponse(
        UUID id,
        String poiId,
        String nameTr,
        String nameEn,
        String category,
        String subcategory,
        Double lat,
        Double lon,
        Double score,
        Integer travelMinutes,
        Integer visitMinutes,
        String startTime,
        String endTime,
        Boolean openAtScheduledTime,
        List<String> reasons,
        Map<String, Double> factors
) {

    static SavedTripStopResponse from(SavedTripStop stop) {
        return new SavedTripStopResponse(
                stop.getId(),
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
                stop.getReasons() == null ? List.of() : stop.getReasons(),
                stop.getFactors() == null ? Map.of() : stop.getFactors());
    }
}