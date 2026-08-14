package com.aitp.orenda.enrichment.wikipedia;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "wikipedia.enrichment.enabled", havingValue = "true")
public class WikipediaEnrichmentJobListener implements JobExecutionListener {

    private final WikipediaEnrichmentStats stats;

    @Override
    public void beforeJob(JobExecution jobExecution) {
        stats.reset();
        log.info("Wikipedia enrichment job started (jobExecutionId={})", jobExecution.getId());
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        log.info(
                "Wikipedia enrichment job finished with status={} — {} POIs enriched, {} not found ({} processed); added {} summaries, {} descriptions, {} images, {} urls ({} source fields stored)",
                jobExecution.getStatus(),
                stats.enrichedCount(),
                stats.notFoundCount(),
                stats.processedCount(),
                stats.summariesCount(),
                stats.descriptionsCount(),
                stats.imagesCount(),
                stats.urlsCount(),
                stats.sourceFieldsCount());
    }
}
