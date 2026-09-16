package com.aitp.orenda.tripadvisor.attractions;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tripadvisor.crawler.attraction-products")
public record AttractionProductCrawlerProperties(
        boolean enabled,
        String baseUrl,
        AttractionProductPaginationMode paginationMode,
        int pageSize,
        int maxEmptyPages,
        int concurrency,
        int maxItems,
        int seeMoreMaxClicks,
        int listingMaxAttempts,
        long listingRetryDelayMs,
        long minDelayMs,
        long maxDelayMs,
        long navigationTimeoutMs,
        boolean headless,
        String userAgent,
        boolean skipListingIfPresent,
        int maxReviewsPerPoi,
        boolean exitWhenDone
) {

    private static final String DEFAULT_BASE_URL =
            "https://www.tripadvisor.com/Attraction_Products-g293974-a_themes.29397400007-Istanbul.html";
    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36";

    public AttractionProductCrawlerProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
        if (paginationMode == null) {
            paginationMode = AttractionProductPaginationMode.NEXT_PAGE_URL;
        }
        if (pageSize < 1) {
            pageSize = 30;
        }
        if (maxEmptyPages < 1) {
            maxEmptyPages = 1;
        }
        if (concurrency < 1) {
            concurrency = 1;
        }
        if (maxItems < 1) {
            maxItems = 110;
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
            userAgent = DEFAULT_USER_AGENT;
        }
        if (maxReviewsPerPoi < 1) {
            maxReviewsPerPoi = 25;
        }
    }
}
