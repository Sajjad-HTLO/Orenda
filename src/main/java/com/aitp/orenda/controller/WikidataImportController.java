package com.aitp.orenda.controller;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST endpoint to manually trigger the Wikidata POI import job.
 * <p>
 * POST /api/wikidata/import
 */
@RestController
@RequestMapping("/api/wikidata")
@ConditionalOnProperty(name = "wikidata.import.enabled", havingValue = "true")
public class WikidataImportController {

    private static final Logger log = LoggerFactory.getLogger(WikidataImportController.class);

    private final JobLauncher jobLauncher;
    private final Job wikidataImportJob;

    public WikidataImportController(
            JobLauncher jobLauncher,
            @Qualifier("wikidataImportJob") Job wikidataImportJob) {
        this.jobLauncher = jobLauncher;
        this.wikidataImportJob = wikidataImportJob;
    }

    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> triggerImport(
            @RequestParam(name = "reset", defaultValue = "false") boolean resetFromStart) {
        try {
            log.info("Manual Wikidata import triggered via REST API (resetFromStart={})", resetFromStart);
            JobParameters params = new JobParametersBuilder()
                    .addLong("runAt", System.currentTimeMillis())
                    .addString("triggeredBy", "rest-api")
                    .addString("resetFromStart", String.valueOf(resetFromStart))
                    .toJobParameters();
            jobLauncher.run(wikidataImportJob, params);

            return ResponseEntity.accepted().body(Map.of(
                    "status", "STARTED",
                    "message", "Wikidata import job launched successfully",
                    "resetFromStart", resetFromStart
            ));
        } catch (Exception e) {
            log.error("Failed to launch Wikidata import job", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "message", e.getMessage()
            ));
        }
    }
}