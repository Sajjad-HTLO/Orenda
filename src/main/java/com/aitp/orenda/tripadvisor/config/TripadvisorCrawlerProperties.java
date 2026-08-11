package com.aitp.orenda.tripadvisor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tripadvisor.crawler")
public record TripadvisorCrawlerProperties(
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

    public TripadvisorCrawlerProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://www.tripadvisor.com/Hotels-g293974-Istanbul-Hotels.html";
        }
        if (concurrency < 1) {
            concurrency = 2;
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
            userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36";
        }
    }
}
