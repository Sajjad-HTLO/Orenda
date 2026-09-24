package com.aitp.orenda.tripadvisor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Single configuration surface for the Tripadvisor listing crawler. One
 * {@code tripadvisor.crawler.base-url} drives the whole run: the listing type
 * (hotels / restaurants / attraction products) is detected from the URL itself,
 * so there is no separate per-type url or enable flag.
 */
@ConfigurationProperties(prefix = "tripadvisor.crawler")
public record TripadvisorCrawlerProperties(
        boolean enabled,
        String baseUrl,
        int concurrency,
        int pageSize,
        int maxEmptyPages,
        boolean singlePageOnly,
        int maxItems,
        int seeMoreMaxClicks,
        int listingMaxAttempts,
        long listingRetryDelayMs,
        boolean skipListingIfPresent,
        int maxReviewsPerPoi,
        boolean exitWhenDone,
        String subcategory,
        long minDelayMs,
        long maxDelayMs,
        long navigationTimeoutMs,
        boolean headless,
        String userAgent,
        String browserProfileDir,
        Boolean detailsEnabled
) {

    public TripadvisorCrawlerProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://www.tripadvisor.com/Hotels-g293974-Istanbul-Hotels.html";
        }
        if (concurrency < 1) {
            concurrency = 1;
        }
        if (pageSize < 1) {
            pageSize = 30;
        }
        if (maxEmptyPages < 1) {
            maxEmptyPages = 1;
        }
        if (maxItems < 1) {
            maxItems = 600;
        }
        if (seeMoreMaxClicks < 1) {
            seeMoreMaxClicks = 30;
        }
        if (listingMaxAttempts < 1) {
            listingMaxAttempts = 10;
        }
        if (listingRetryDelayMs < 1000) {
            listingRetryDelayMs = 180_000;
        }
        if (maxReviewsPerPoi < 1) {
            maxReviewsPerPoi = 25;
        }
        if (subcategory == null || subcategory.isBlank()) {
            subcategory = "dinner_cruise";
        }
        if (minDelayMs < 0) {
            minDelayMs = 2500;
        }
        if (maxDelayMs < minDelayMs) {
            maxDelayMs = minDelayMs;
        }
        if (navigationTimeoutMs < 1000) {
            navigationTimeoutMs = 60000;
        }
        if (userAgent == null || userAgent.isBlank()) {
            userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36";
        }
        if (browserProfileDir == null || browserProfileDir.isBlank()) {
            browserProfileDir = "data/tripadvisor-browser-profile";
        }
        if (detailsEnabled == null) {
            detailsEnabled = true;
        }
    }
}
