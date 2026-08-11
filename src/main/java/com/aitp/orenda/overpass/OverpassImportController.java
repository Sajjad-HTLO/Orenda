package com.aitp.orenda.overpass;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST endpoint to manually trigger the Overpass POI import job.
 * <p>
 * POST /api/overpass/import
 * <p>
 * The job fetches tourist POIs from the Overpass API (free, no auth) for 75
 * tag-based categories within Turkey's bounding box and batch-inserts/updates
 * them into the {@code poi} table.
 */
@RestController
@RequestMapping("/api/overpass")
@ConditionalOnProperty(name = "overpass.import.enabled", havingValue = "true")
public class OverpassImportController {

    private static final Logger log = LoggerFactory.getLogger(OverpassImportController.class);

    private final JobLauncher jobLauncher;
    private final Job overpassImportJob;

    public OverpassImportController(
            JobLauncher jobLauncher,
            @Qualifier("overpassImportJob") Job overpassImportJob) {
        this.jobLauncher = jobLauncher;
        this.overpassImportJob = overpassImportJob;
    }

    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> triggerImport() {
        try {
            log.info("Manual Overpass import triggered via REST API");
            JobParameters params = new JobParametersBuilder()
                    .addLong("runAt", System.currentTimeMillis())
                    .addString("triggeredBy", "rest-api")
                    .toJobParameters();
            jobLauncher.run(overpassImportJob, params);

            return ResponseEntity.accepted().body(Map.of(
                    "status", "STARTED",
                    "message", "Overpass import job launched successfully"
            ));
        } catch (Exception e) {
            log.error("Failed to launch Overpass import job", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "message", e.getMessage()
            ));
        }
    }
}