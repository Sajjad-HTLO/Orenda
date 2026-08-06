package com.sajad.AITP.tripadvisor.crawler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import com.sajad.AITP.tripadvisor.config.TripadvisorCrawlerProperties;

@Slf4j
@Configuration
@EnableConfigurationProperties(TripadvisorCrawlerProperties.class)
@ConditionalOnProperty(name = "tripadvisor.crawler.enabled", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TripadvisorCrawlerRunner implements CommandLineRunner {

    private final CrawlManager crawlManager;
    private final TripadvisorCrawlerProperties properties;

    public TripadvisorCrawlerRunner(CrawlManager crawlManager, TripadvisorCrawlerProperties properties) {
        this.crawlManager = crawlManager;
        this.properties = properties;
    }

    @Override
    public void run(String... args) {
        long startedAt = System.currentTimeMillis();
        log.info("Tripadvisor crawler CommandLineRunner triggered; starting listing-page stage. baseUrl={}, concurrency={}, singlePageOnly={}, headless={}",
                properties.baseUrl(), properties.concurrency(), properties.singlePageOnly(), properties.headless());
        try {
            crawlManager.crawlListingPages();
            log.info("Tripadvisor crawler CommandLineRunner finished successfully in {}ms", System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.error("Tripadvisor crawler CommandLineRunner failed after {}ms: {}", System.currentTimeMillis() - startedAt, e.getMessage(), e);
            throw e;
        }
    }
}
