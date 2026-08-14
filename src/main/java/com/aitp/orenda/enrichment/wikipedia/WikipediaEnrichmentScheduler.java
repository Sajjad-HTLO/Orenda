package com.aitp.orenda.enrichment.wikipedia;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "wikipedia.enrichment.enabled", havingValue = "true")
public class WikipediaEnrichmentScheduler {

    private final JobLauncher jobLauncher;
    private final Job wikipediaEnrichmentJob;

    public WikipediaEnrichmentScheduler(
            JobLauncher jobLauncher,
            @Qualifier("wikipediaEnrichmentJob") Job wikipediaEnrichmentJob) {
        this.jobLauncher = jobLauncher;
        this.wikipediaEnrichmentJob = wikipediaEnrichmentJob;
    }

    @Scheduled(fixedDelayString = "${wikipedia.enrichment.fixed-delay-ms:60000}")
    public void runScheduledEnrichment() throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addLong("runAt", System.currentTimeMillis())
                .toJobParameters();
        jobLauncher.run(wikipediaEnrichmentJob, params);
    }
}
