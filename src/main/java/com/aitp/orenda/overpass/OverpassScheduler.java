package com.aitp.orenda.overpass;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the Overpass import job on a schedule.
 * <p>
 * Default: once per week (604800000ms = 7 days). Overpass data changes slowly,
 * so weekly updates are sufficient for catching new/updated OSM elements.
 * <p>
 * The first run will happen on startup (initialDelay = 0) so you get data immediately.
 * <p>
 * Note: The Overpass API has a ~10,000 requests/day fair-use limit. With ~70 category
 * queries and 5-second delays between them, a full import takes ~6 minutes and uses
 * ~70 requests — well within the daily limit.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "overpass.import.enabled", havingValue = "true")
public class OverpassScheduler {

    private static final Logger log = LoggerFactory.getLogger(OverpassScheduler.class);

    private final JobLauncher jobLauncher;
    private final Job overpassImportJob;

    public OverpassScheduler(
            JobLauncher jobLauncher,
            @Qualifier("overpassImportJob") Job overpassImportJob) {
        this.jobLauncher = jobLauncher;
        this.overpassImportJob = overpassImportJob;
    }

    /**
     * Scheduled Overpass import. First run is immediate (initialDelay=0),
     * subsequent runs follow the configured fixed delay.
     */
    @Scheduled(
            initialDelayString = "${overpass.import.initial-delay-ms:0}",
            fixedDelayString = "${overpass.import.fixed-delay-ms:604800000}")
    public void runScheduledImport() throws Exception {
        log.info("Starting scheduled Overpass import job");
        JobParameters params = new JobParametersBuilder()
                .addLong("runAt", System.currentTimeMillis())
                .toJobParameters();
        JobExecution execution = jobLauncher.run(overpassImportJob, params);
        log.info("Overpass import job completed with status: {}", execution.getStatus());
    }
}