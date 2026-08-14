package com.aitp.orenda.service;

import com.aitp.orenda.exception.PoiNotFoundException;
import com.aitp.orenda.model.FeedbackRequest;
import com.aitp.orenda.model.FeedbackResponse;
import com.aitp.orenda.model.FeedbackType;
import com.aitp.orenda.model.PoiResponse;
import com.aitp.orenda.repository.PoiRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Coordinates the immediate-feedback flow: validates the POI exists, persists
 * the feedback (which updates the POI in the same transaction), then finds a
 * nearby alternative POI to suggest.
 */
@Service
@RequiredArgsConstructor
public class PoiFeedbackService {

    /**
     * Radius used to look for an alternative POI around the reported one.
     */
    private static final double ALTERNATIVE_RADIUS_KM = 3.0;

    private final PoiRepository poiRepository;

    public FeedbackResponse process(FeedbackRequest req) {
        PoiResponse reported = poiRepository.findById(req.getPoiId())
                .orElseThrow(() -> new PoiNotFoundException(req.getPoiId()));

        poiRepository.saveFeedback(req);

        Optional<PoiResponse> alternative = poiRepository.findAlternative(
                req.getPoiId(), reported.getCategory(),
                reported.getLat(), reported.getLon(), ALTERNATIVE_RADIUS_KM);

        return FeedbackResponse.builder()
                .accepted(true)
                .message(messageFor(req.getType()))
                .alternativePoi(alternative.orElse(null))
                .build();
    }

    private String messageFor(FeedbackType type) {
        return switch (type) {
            case CLOSED -> "Feedback received — POI marked as closed and excluded from future results.";
            case DUPLICATE -> "Feedback received — POI marked as duplicate and excluded from future results.";
            case MOVED -> "Feedback received — POI location flagged for review.";
            case INACCURATE -> "Feedback received — POI data flagged for review.";
            case OTHER -> "Feedback received — thank you for the report.";
        };
    }
}
