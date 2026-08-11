package com.aitp.orenda.enrichment.wikipedia;

import com.aitp.orenda.enrichment.PoiEnrichmentCandidate;
import com.aitp.orenda.enrichment.PoiEnrichmentResult;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty(name = "wikipedia.enrichment.enabled", havingValue = "true")
public class WikipediaEnrichmentJobConfig {

    @Bean
    public TaskExecutor wikipediaEnrichmentTaskExecutor(
            @Value("${wikipedia.enrichment.parallelism:3}") int parallelism) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(parallelism);
        executor.setMaxPoolSize(parallelism);
        executor.setThreadNamePrefix("wiki-enrich-");
        executor.initialize();
        return executor;
    }

    @Bean
    public Job wikipediaEnrichmentJob(
            JobRepository jobRepository,
            Step wikipediaEnrichmentMasterStep,
            WikipediaEnrichmentJobListener jobListener) {
        return new JobBuilder("wikipediaEnrichmentJob", jobRepository)
                .listener(jobListener)
                .start(wikipediaEnrichmentMasterStep)
                .build();
    }

    @Bean
    public Step wikipediaEnrichmentMasterStep(
            JobRepository jobRepository,
            Step wikipediaEnrichmentWorkerStep,
            Partitioner wikipediaEnrichmentPartitioner,
            TaskExecutor wikipediaEnrichmentTaskExecutor,
            @Value("${wikipedia.enrichment.parallelism:3}") int parallelism) {
        return new StepBuilder("wikipediaEnrichmentMasterStep", jobRepository)
                .partitioner("wikipediaEnrichmentWorkerStep", wikipediaEnrichmentPartitioner)
                .step(wikipediaEnrichmentWorkerStep)
                .gridSize(parallelism)
                .taskExecutor(wikipediaEnrichmentTaskExecutor)
                .build();
    }

    @Bean
    public Step wikipediaEnrichmentWorkerStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            WikipediaEnrichmentItemReader reader,
            WikipediaEnrichmentProcessor processor,
            WikipediaEnrichmentItemWriter writer,
            @Value("${wikipedia.enrichment.chunk-size:1}") int chunkSize) {
        return new StepBuilder("wikipediaEnrichmentWorkerStep", jobRepository)
                .<PoiEnrichmentCandidate, PoiEnrichmentResult>chunk(chunkSize)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .transactionManager(transactionManager)
                .build();
    }
}
