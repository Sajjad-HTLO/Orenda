package com.aitp.orenda.muzegov;

import com.aitp.orenda.model.PoiEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Spring Batch job for importing museums and archaeological sites from muze.gov.tr.
 * <p>
 * Enable with: {@code muzegov.import.enabled=true}
 */
@Configuration
@ConditionalOnProperty(name = "muzegov.import.enabled", havingValue = "true")
public class MuzeGovTrJobConfig {

    private static final Logger log = LoggerFactory.getLogger(MuzeGovTrJobConfig.class);

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final MuzeGovTrItemReader reader;
    private final MuzeGovTrProcessor processor;
    private final MuzeGovTrJdbcItemWriter writer;
    private final int chunkSize;

    public MuzeGovTrJobConfig(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            MuzeGovTrItemReader reader,
            MuzeGovTrProcessor processor,
            MuzeGovTrJdbcItemWriter writer,
            @Value("${muzegov.import.chunk-size:50}") int chunkSize) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.reader = reader;
        this.processor = processor;
        this.writer = writer;
        this.chunkSize = chunkSize;
    }

    @Bean
    public Job muzeGovTrImportJob(Step muzeGovTrImportStep) {
        return new JobBuilder("muzeGovTrImportJob", jobRepository)
                .start(muzeGovTrImportStep)
                .build();
    }

    @Bean
    public Step muzeGovTrImportStep() {
        return new StepBuilder("muzeGovTrImportStep", jobRepository)
                .<MuzeGovTrRawPoi, PoiEntity>chunk(chunkSize)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .transactionManager(transactionManager)
                .build();
    }
}