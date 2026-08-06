package com.sajad.AITP.overpass;

import com.sajad.AITP.model.PoiEntity;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch job configuration for importing tourist POIs from the Overpass API.
 * <p>
 * Unlike the OSM PBF import (which is a one-shot), this job is designed to be run
 * on demand or on a schedule. It fetches all configured tag categories from Overpass
 * and batch-inserts/updates them into the {@code poi} table.
 * <p>
 * Enable with: {@code overpass.import.enabled=true}
 */
@Configuration
@ConditionalOnProperty(name = "overpass.import.enabled", havingValue = "true")
public class OverpassJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final OverpassItemReader reader;
    private final OverpassProcessor processor;
    private final OverpassJdbcItemWriter writer;
    private final int chunkSize;

    public OverpassJobConfig(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            OverpassItemReader reader,
            OverpassProcessor processor,
            OverpassJdbcItemWriter writer,
            @Value("${overpass.import.chunk-size:100}") int chunkSize) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.reader = reader;
        this.processor = processor;
        this.writer = writer;
        this.chunkSize = chunkSize;
    }

    @Bean
    public Job overpassImportJob(Step overpassImportStep) {
        return new JobBuilder("overpassImportJob", jobRepository)
                .start(overpassImportStep)
                .build();
    }

    @Bean
    public Step overpassImportStep() {
        return new StepBuilder("overpassImportStep", jobRepository)
                .<OverpassRawPoi, PoiEntity>chunk(chunkSize)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .transactionManager(transactionManager)
                .build();
    }
}