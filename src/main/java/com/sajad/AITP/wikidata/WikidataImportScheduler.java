package com.sajad.AITP.wikidata;

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
 * Runs the Wikidata import job on a schedule.
 * <p>
 * Default: once per week (604800000ms = 7 days). Wikidata data changes slowly,
 * so weekly updates are sufficient for catching new/updated items.
 * <p>
 * The first run will happen on startup (initialDelay = 0) so you get data immediately.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "wikidata.import.enabled", havingValue = "true")
public class WikidataImportScheduler {

    private static final Logger log = LoggerFactory.getLogger(WikidataImportScheduler.class);

    private final JobLauncher jobLauncher;
    private final Job wikidataImportJob;

    public WikidataImportScheduler(
            JobLauncher jobLauncher,
            @Qualifier("wikidataImportJob") Job wikidataImportJob) {
        this.jobLauncher = jobLauncher;
        this.wikidataImportJob = wikidataImportJob;
    }

    /**
     * Scheduled Wikidata import. First run is immediate (initialDelay=0),
     * subsequent runs follow the configured fixed delay.
     */
    @Scheduled(
            initialDelayString = "${wikidata.import.initial-delay-ms:0}",
            fixedDelayString = "${wikidata.import.fixed-delay-ms:604800000}")
    public void runScheduledImport() throws Exception {
        log.info("Starting scheduled Wikidata import job");
        JobParameters params = new JobParametersBuilder()
                .addLong("runAt", System.currentTimeMillis())
                .toJobParameters();
        JobExecution execution = jobLauncher.run(wikidataImportJob, params);
        log.info("Wikidata import job completed with status: {}", execution.getStatus());
    }
}