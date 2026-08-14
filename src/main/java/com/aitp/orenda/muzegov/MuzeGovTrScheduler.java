package com.aitp.orenda.muzegov;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the muze.gov.tr import on a schedule (once per week by default).
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "muzegov.import.enabled", havingValue = "true")
public class MuzeGovTrScheduler {

    private static final Logger log = LoggerFactory.getLogger(MuzeGovTrScheduler.class);

    private final JobLauncher jobLauncher;
    private final Job muzeGovTrImportJob;

    public MuzeGovTrScheduler(
            JobLauncher jobLauncher,
            @Qualifier("muzeGovTrImportJob") Job muzeGovTrImportJob) {
        this.jobLauncher = jobLauncher;
        this.muzeGovTrImportJob = muzeGovTrImportJob;
    }

    @Scheduled(
            initialDelayString = "${muzegov.import.initial-delay-ms:10000}",
            fixedDelayString = "${muzegov.import.fixed-delay-ms:604800000}")
    public void runScheduledImport() throws Exception {
        log.info("Starting scheduled muze.gov.tr import");
        JobParameters params = new JobParametersBuilder()
                .addLong("runAt", System.currentTimeMillis())
                .toJobParameters();
        jobLauncher.run(muzeGovTrImportJob, params);
        log.info("muze.gov.tr import completed");
    }
}