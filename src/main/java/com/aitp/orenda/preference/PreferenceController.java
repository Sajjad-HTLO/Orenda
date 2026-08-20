package com.aitp.orenda.preference;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Immediate feedback + learned preference endpoints.
 * <p>
 * <ul>
 *   <li>GET  /api/preferences/{sessionId}      — current per-category weights</li>
 *   <li>POST /api/preferences/feedback         — like / dislike / love / not interested / rated</li>
 *   <li>POST /api/preferences/{sessionId}/enrich — launch data enrichment prioritized by the
 *       traveler's learned weights (loved categories enriched first)</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/preferences")
@RequiredArgsConstructor
public class PreferenceController {

    private final PreferenceService preferenceService;
    private final JobLauncher jobLauncher;
    @Qualifier("wikipediaEnrichmentJob")
    private final ObjectProvider<Job> wikipediaEnrichmentJob;

    @GetMapping("/{sessionId}")
    public ResponseEntity<Map<String, Double>> weights(@PathVariable String sessionId) {
        return ResponseEntity.ok(preferenceService.overviewWeights(sessionId));
    }

    @PostMapping("/feedback")
    public ResponseEntity<PreferenceFeedbackResponse> feedback(
            @Valid @RequestBody PreferenceFeedbackRequest request) {
        return ResponseEntity.ok(preferenceService.processFeedback(request));
    }

    /**
     * Launches the Wikipedia enrichment job with this session's learned weights,
     * so POIs in the categories the traveler loves are enriched first.
     */
    @PostMapping("/{sessionId}/enrich")
    public ResponseEntity<Map<String, Object>> enrichForSession(@PathVariable String sessionId) {
        Job job = wikipediaEnrichmentJob.getIfAvailable();
        if (job == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("accepted", false,
                            "message", "Enrichment is disabled (wikipedia.enrichment.enabled=false)."));
        }
        try {
            JobParameters params = new JobParametersBuilder()
                    .addString("sessionId", sessionId)
                    .addLong("runAt", System.currentTimeMillis())
                    .toJobParameters();
            JobExecution execution = jobLauncher.run(job, params);
            return ResponseEntity.ok(Map.of(
                    "accepted", true,
                    "status", execution.getStatus().name()));
        } catch (Exception e) {
            log.error("Failed to launch preference-prioritized enrichment for session {}", sessionId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("accepted", false, "message", e.getMessage()));
        }
    }
}