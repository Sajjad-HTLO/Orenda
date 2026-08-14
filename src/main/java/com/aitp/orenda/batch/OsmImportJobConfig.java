package com.aitp.orenda.batch;

import com.aitp.orenda.model.OsmPoi;
import com.aitp.orenda.model.PoiEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class OsmImportJobConfig {

    private static final Logger log = LoggerFactory.getLogger(OsmImportJobConfig.class);

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final OsmPoiItemReader reader;
    private final OsmPoiItemProcessor processor;
    private final PoiJdbcItemWriter writer;
    private final int chunkSize;

    public OsmImportJobConfig(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            OsmPoiItemReader reader,
            OsmPoiItemProcessor processor,
            PoiJdbcItemWriter writer,
            @Value("${osm.import.chunk-size:500}") int chunkSize) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.reader = reader;
        this.processor = processor;
        this.writer = writer;
        this.chunkSize = chunkSize;
    }

    @Bean
    public Job osmImportJob(Step osmImportStep) {
        return new JobBuilder("osmImportJob", jobRepository)
                .start(osmImportStep)
                .build();
    }

    @Bean
    public Step osmImportStep() {
        return new StepBuilder("osmImportStep", jobRepository)
                .<OsmPoi, PoiEntity>chunk(chunkSize)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .transactionManager(transactionManager)
                .build();
    }
}