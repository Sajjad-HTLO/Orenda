package com.aitp.orenda.tripadvisor.restaurants;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tripadvisor.crawler.restaurants")
public record RestaurantCrawlerProperties(
        boolean enabled,
        String baseUrl,
        int concurrency,
        int pageSize,
        int maxEmptyPages,
        boolean singlePageOnly,
        long minDelayMs,
        long maxDelayMs,
        long navigationTimeoutMs,
        boolean headless,
        String userAgent
) {

    private static final String DEFAULT_BASE_URL =
            "https://www.tripadvisor.com/Restaurants-g293974-Istanbul.html";
    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36";

    public RestaurantCrawlerProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
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
    }
}