package com.aitp.orenda.tripadvisor.attractions;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@Slf4j
@Configuration
@EnableConfigurationProperties(AttractionProductCrawlerProperties.class)
@ConditionalOnProperty(name = "tripadvisor.crawler.attraction-products.enabled", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class AttractionProductCrawlerRunner implements CommandLineRunner {

    private final AttractionProductCrawlerManager crawlerManager;
    private final AttractionProductCrawlerProperties properties;
    private final ApplicationContext applicationContext;

    public AttractionProductCrawlerRunner(
            AttractionProductCrawlerManager crawlerManager,
            AttractionProductCrawlerProperties properties,
            ApplicationContext applicationContext) {
        this.crawlerManager = crawlerManager;
        this.properties = properties;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(String... args) {
        long startedAt = System.currentTimeMillis();
        log.info("Tripadvisor attraction product crawler CommandLineRunner triggered. baseUrl={}, maxItems={}, headless={}, exitWhenDone={}",
                properties.baseUrl(), properties.maxItems(), properties.headless(), properties.exitWhenDone());
        try {
            crawlerManager.crawl();
            log.info("Tripadvisor attraction product crawler CommandLineRunner finished successfully in {}ms",
                    System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.error("Tripadvisor attraction product crawler CommandLineRunner failed after {}ms: {}",
                    System.currentTimeMillis() - startedAt, e.getMessage(), e);
            throw e;
        } finally {
            // All POIs of the URL have been crawled (listing + details). Stop the
            // application instead of leaving the web server running.
            if (properties.exitWhenDone()) {
                log.info("Tripadvisor attraction product crawler done; shutting down application (exit-when-done=true).");
                int exitCode = SpringApplication.exit(applicationContext, () -> 0);
                System.exit(exitCode);
            }
        }
    }
}
