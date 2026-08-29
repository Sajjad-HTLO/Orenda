package com.aitp.orenda.review;

import com.aitp.orenda.auth.UserEntity;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Traveler reviews for a POI.
 * <ul>
 *   <li>GET  /api/pois/{id}/reviews — public list of reviews</li>
 *   <li>POST /api/pois/{id}/reviews — authenticated user submits a review/rating</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/pois/{id}/reviews")
@RequiredArgsConstructor
public class PoiReviewController {

    private final PoiReviewRepository reviewRepository;

    @GetMapping
    public ResponseEntity<List<PoiReviewResponse>> list(@PathVariable String id) {
        UUID poiId = parseId(id);
        if (poiId == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(reviewRepository.findByPoi(poiId).stream()
                .map(PoiReviewResponse::from)
                .toList());
    }

    @PostMapping
    public ResponseEntity<PoiReviewResponse> submit(@PathVariable String id,
                                                    @Valid @RequestBody PoiReviewRequest request,
                                                    @AuthenticationPrincipal UserEntity user) {
        UUID poiId = parseId(id);
        if (poiId == null || !reviewRepository.poiExists(poiId)) {
            return ResponseEntity.notFound().build();
        }
        String travelerName = user == null ? null : user.getFullName();
        PoiReview saved = reviewRepository.insert(new PoiReview(
                null,
                poiId,
                user == null ? null : user.getId(),
                travelerName,
                request.rating(),
                request.title(),
                request.comment(),
                null));
        return ResponseEntity.status(HttpStatus.CREATED).body(PoiReviewResponse.from(saved));
    }

    private UUID parseId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}