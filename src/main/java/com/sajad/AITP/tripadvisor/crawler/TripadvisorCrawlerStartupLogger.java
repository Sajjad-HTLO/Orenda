package com.sajad.AITP.tripadvisor.crawler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TripadvisorCrawlerStartupLogger implements ApplicationRunner {

    private final boolean enabled;
    private final String baseUrl;
    private final int concurrency;
    private final int pageSize;
    private final int maxEmptyPages;
    private final boolean singlePageOnly;
    private final long minDelayMs;
    private final long maxDelayMs;
    private final long navigationTimeoutMs;
    private final boolean headless;

    public TripadvisorCrawlerStartupLogger(
            @Value("${tripadvisor.crawler.enabled:false}") boolean enabled,
            @Value("${tripadvisor.crawler.base-url:https://www.tripadvisor.com/Hotels-g293974-Istanbul-Hotels.html}") String baseUrl,
            @Value("${tripadvisor.crawler.concurrency:2}") int concurrency,
            @Value("${tripadvisor.crawler.page-size:30}") int pageSize,
            @Value("${tripadvisor.crawler.max-empty-pages:1}") int maxEmptyPages,
            @Value("${tripadvisor.crawler.single-page-only:false}") boolean singlePageOnly,
            @Value("${tripadvisor.crawler.min-delay-ms:2500}") long minDelayMs,
            @Value("${tripadvisor.crawler.max-delay-ms:7000}") long maxDelayMs,
            @Value("${tripadvisor.crawler.navigation-timeout-ms:60000}") long navigationTimeoutMs,
            @Value("${tripadvisor.crawler.headless:true}") boolean headless) {
        this.enabled = enabled;
        this.baseUrl = baseUrl;
        this.concurrency = concurrency;
        this.pageSize = pageSize;
        this.maxEmptyPages = maxEmptyPages;
        this.singlePageOnly = singlePageOnly;
        this.minDelayMs = minDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.navigationTimeoutMs = navigationTimeoutMs;
        this.headless = headless;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("Tripadvisor crawler is DISABLED. Set tripadvisor.crawler.enabled=true to run it on startup.");
            return;
        }

        log.info("Tripadvisor crawler is ENABLED. baseUrl={}, concurrency={}, pageSize={}, maxEmptyPages={}, singlePageOnly={}, delayRangeMs={}..{}, navigationTimeoutMs={}, headless={}",
                baseUrl, concurrency, pageSize, maxEmptyPages, singlePageOnly, minDelayMs, maxDelayMs, navigationTimeoutMs, headless);
    }
}
