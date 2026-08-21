package com.aitp.orenda.tripadvisor.restaurants;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@Slf4j
@Configuration
@EnableConfigurationProperties(RestaurantCrawlerProperties.class)
@ConditionalOnProperty(name = "tripadvisor.crawler.restaurants.enabled", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RestaurantCrawlerRunner implements CommandLineRunner {

    private final RestaurantCrawlerManager crawlerManager;
    private final RestaurantCrawlerProperties properties;

    public RestaurantCrawlerRunner(
            RestaurantCrawlerManager crawlerManager,
            RestaurantCrawlerProperties properties) {
        this.crawlerManager = crawlerManager;
        this.properties = properties;
    }

    @Override
    public void run(String... args) {
        long startedAt = System.currentTimeMillis();
        log.info("Tripadvisor restaurant crawler CommandLineRunner triggered. baseUrl={}, headless={}",
                properties.baseUrl(), properties.headless());
        try {
            crawlerManager.crawl();
            log.info("Tripadvisor restaurant crawler CommandLineRunner finished successfully in {}ms",
                    System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.error("Tripadvisor restaurant crawler CommandLineRunner failed after {}ms: {}",
                    System.currentTimeMillis() - startedAt, e.getMessage(), e);
            throw e;
        }
    }
}