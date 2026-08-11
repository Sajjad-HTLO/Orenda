package com.sajad.AITP.controller;

import com.sajad.AITP.exception.PoiNotFoundException;
import com.sajad.AITP.model.FeedbackRequest;
import com.sajad.AITP.model.FeedbackResponse;
import com.sajad.AITP.model.PoiResponse;
import com.sajad.AITP.repository.PoiRepository;
import com.sajad.AITP.service.PoiFeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/pois")
@RequiredArgsConstructor
public class PoiController {

    private static final int MAX_SIZE      = 100;
    private static final double MAX_RADIUS = 50.0; // km

    private final PoiRepository poiRepository;
    private final PoiFeedbackService poiFeedbackService;

    /**
     * POIs within a radius, optionally filtered by category.
     * GET /api/pois/nearby?lat=41.01&lon=28.97&radiusKm=5&category=historic&page=0&size=20
     */
    @GetMapping("/nearby")
    public List<PoiResponse> nearby(
            @RequestParam double lat,
            @RequestParam double lon,
            @RequestParam(defaultValue = "10")  double radiusKm,
            @RequestParam(required = false)     String category,
            @RequestParam(defaultValue = "0")   int page,
            @RequestParam(defaultValue = "20")  int size) {

        return poiRepository.findNearby(
            lat, lon,
            Math.min(radiusKm, MAX_RADIUS),
            category,
            page,
            Math.min(size, MAX_SIZE));
    }

    /**
     * Single POI by its UUID.
     * GET /api/pois/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<PoiResponse> getById(@PathVariable String id) {
        return poiRepository.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Full-text search over Turkish and English names.
     * GET /api/pois/search?q=topkapi&page=0&size=20
     */
    @GetMapping("/search")
    public List<PoiResponse> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        return poiRepository.search(q, page, Math.min(size, MAX_SIZE));
    }

    /**
     * All categories and subcategories with POI counts.
     * GET /api/pois/categories
     */
    @GetMapping("/categories")
    public List<Map<String, Object>> categories() {
        return poiRepository.findCategories();
    }

    /**
     * Immediate user feedback for a suggested POI (closed, inaccurate, moved,
     * duplicate, other). Updates the POI data right away and returns the nearest
     * alternative POI to suggest instead.
     * POST /api/pois/feedback
     */
    @PostMapping("/feedback")
    public ResponseEntity<FeedbackResponse> feedback(@Valid @RequestBody FeedbackRequest request) {
        try {
            return ResponseEntity.ok(poiFeedbackService.process(request));
        } catch (PoiNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
