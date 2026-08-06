package com.sajad.AITP.tripadvisor.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "tripadvisor.crawler.enabled", havingValue = "true")
public class TripadvisorDatabaseConfig {
    /**
     * Tripadvisor persistence intentionally uses the application's primary PostgreSQL datasource.
     * Hotel listings are upserted into the shared poi table; only crawl-page progress is kept
     * in a Tripadvisor-specific metadata table.
     */
}
