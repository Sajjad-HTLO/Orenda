package com.sajad.AITP.tripadvisor.util;

import com.sajad.AITP.tripadvisor.config.TripadvisorCrawlerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
@ConditionalOnProperty(name = "tripadvisor.crawler.enabled", havingValue = "true")
public class RandomDelay {

    private final TripadvisorCrawlerProperties properties;

    public RandomDelay(TripadvisorCrawlerProperties properties) {
        this.properties = properties;
    }

    public void pause() {
        long min = properties.minDelayMs();
        long max = properties.maxDelayMs();
        long delay = max <= min ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
        if (delay <= 0) {
            return;
        }
        try {
            log.info("Tripadvisor crawler delay: sleeping {}ms before next request", delay);
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during Tripadvisor crawler delay", e);
        }
    }

    /**
     * Longer pause used when retrying after a DataDome block.
     * Waits 10-20 seconds to let the anti-bot cooldown period expire.
     */
    public void pauseLonger() {
        long delay = ThreadLocalRandom.current().nextLong(10_000, 20_001);
        try {
            log.info("Tripadvisor crawler: longer delay {}ms after DataDome block before retry", delay);
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during Tripadvisor crawler longer delay", e);
        }
    }
}
