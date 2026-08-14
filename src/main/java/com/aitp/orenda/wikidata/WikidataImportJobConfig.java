package com.aitp.orenda.wikidata;

import com.aitp.orenda.model.PoiEntity;
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
 * Spring Batch job configuration for importing tourist POIs from Wikidata SPARQL.
 * <p>
 * Unlike the OSM import (which is a one-shot), this job is designed to be run on
 * demand or on a schedule. It fetches all categories from Wikidata and batch-inserts
 * them into the {@code poi} table.
 * <p>
 * Enable with: {@code wikidata.import.enabled=true}
 */
@Configuration
@ConditionalOnProperty(name = "wikidata.import.enabled", havingValue = "true")
public class WikidataImportJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final WikidataSparqlReader reader;
    private final WikidataPoiProcessor processor;
    private final WikidataJdbcItemWriter writer;
    private final int chunkSize;

    public WikidataImportJobConfig(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            WikidataSparqlReader reader,
            WikidataPoiProcessor processor,
            WikidataJdbcItemWriter writer,
            @Value("${wikidata.import.chunk-size:100}") int chunkSize) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.reader = reader;
        this.processor = processor;
        this.writer = writer;
        this.chunkSize = chunkSize;
    }

    @Bean
    public Job wikidataImportJob(Step wikidataImportStep) {
        return new JobBuilder("wikidataImportJob", jobRepository)
                .start(wikidataImportStep)
                .build();
    }

    @Bean
    public Step wikidataImportStep() {
        return new StepBuilder("wikidataImportStep", jobRepository)
                .<WikidataRawPoi, PoiEntity>chunk(chunkSize)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .transactionManager(transactionManager)
                .build();
    }
}