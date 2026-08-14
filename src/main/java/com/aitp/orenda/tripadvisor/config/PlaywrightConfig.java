package com.aitp.orenda.tripadvisor.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "tripadvisor.crawler.enabled", havingValue = "true")
public class PlaywrightConfig {
    /**
     * Playwright Java objects are not thread-safe. ListingWorker creates a Playwright/browser
     * instance inside each virtual-thread task so concurrent listing workers do not share
     * Playwright channel objects.
     */
}
