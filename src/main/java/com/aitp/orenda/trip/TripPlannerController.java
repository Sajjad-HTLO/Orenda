package com.aitp.orenda.trip;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST endpoint for trip planning.
 * <p>
 * POST /api/trips/plan — accepts the full questionnaire and returns ranked POI suggestions
 * plus an optional day-by-day plan.
 */
@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class TripPlannerController {

    private final TripRecommendationService recommendationService;

    /**
     * Generate a trip plan from the questionnaire.
     * <p>
     * Request body: {@link TripPlanRequest}
     * Response:     {@link TripPlanResponse}
     */
    @PostMapping("/plan")
    public ResponseEntity<TripPlanResponse> planTrip(@Valid @RequestBody TripPlanRequest request) {
        TripPlanResponse response = recommendationService.recommend(request);
        return ResponseEntity.ok(response);
    }
}