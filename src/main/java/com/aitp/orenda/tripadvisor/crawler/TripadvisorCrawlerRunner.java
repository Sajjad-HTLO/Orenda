package com.aitp.orenda.tripadvisor.crawler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import com.aitp.orenda.tripadvisor.attractions.AttractionProductCrawlerManager;
import com.aitp.orenda.tripadvisor.config.TripadvisorCrawlerProperties;
import com.aitp.orenda.tripadvisor.restaurants.RestaurantCrawlerManager;

/**
 * Single entry point for the Tripadvisor crawler. The listing type is detected
 * automatically from {@code tripadvisor.crawler.base-url} and the matching
 * pipeline (hotels, restaurants or attraction products) is run.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(TripadvisorCrawlerProperties.class)
@ConditionalOnProperty(name = "tripadvisor.crawler.enabled", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TripadvisorCrawlerRunner implements CommandLineRunner {

    private final TripadvisorCrawlerProperties properties;
    private final CrawlManager crawlManager;
    private final RestaurantCrawlerManager restaurantCrawlerManager;
    private final AttractionProductCrawlerManager attractionProductCrawlerManager;
    private final ApplicationContext applicationContext;

    public TripadvisorCrawlerRunner(
            TripadvisorCrawlerProperties properties,
            CrawlManager crawlManager,
            RestaurantCrawlerManager restaurantCrawlerManager,
            AttractionProductCrawlerManager attractionProductCrawlerManager,
            ApplicationContext applicationContext) {
        this.properties = properties;
        this.crawlManager = crawlManager;
        this.restaurantCrawlerManager = restaurantCrawlerManager;
        this.attractionProductCrawlerManager = attractionProductCrawlerManager;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(String... args) {
        long startedAt = System.currentTimeMillis();
        TripadvisorListingType listingType = TripadvisorListingType.detect(properties.baseUrl());
        log.info("Tripadvisor crawler CommandLineRunner triggered. baseUrl={}, detectedListingType={}, concurrency={}, pageSize={}, minDelayMs={}, maxDelayMs={}, headless={}, exitWhenDone={}",
                properties.baseUrl(), listingType, properties.concurrency(), properties.pageSize(),
                properties.minDelayMs(), properties.maxDelayMs(), properties.headless(), properties.exitWhenDone());
        try {
            switch (listingType) {
                case HOTEL -> crawlManager.crawlListingPages();
                case RESTAURANT -> restaurantCrawlerManager.crawl();
                case TOUR_PRODUCT, ATTRACTION -> attractionProductCrawlerManager.crawl();
            }
            log.info("Tripadvisor {} crawler CommandLineRunner finished successfully in {}ms",
                    listingType.name().toLowerCase(), System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.error("Tripadvisor crawler CommandLineRunner failed after {}ms: {}", System.currentTimeMillis() - startedAt, e.getMessage(), e);
            throw e;
        } finally {
            // The whole configured listing URL has been crawled. Stop the
            // application instead of leaving the web server running.
            if (properties.exitWhenDone()) {
                log.info("Tripadvisor crawler done; shutting down application (exit-when-done=true).");
                int exitCode = SpringApplication.exit(applicationContext, () -> 0);
                System.exit(exitCode);
            }
        }
    }
}
