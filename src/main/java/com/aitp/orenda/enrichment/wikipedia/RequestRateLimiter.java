package com.aitp.orenda.enrichment.wikipedia;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class RequestRateLimiter {

    private final long minIntervalMs;
    private final AtomicLong nextAvailableAt;

    public RequestRateLimiter(@Value("${wikipedia.enrichment.min-interval-ms:1000}") long minIntervalMs) {
        this.minIntervalMs = Math.max(minIntervalMs, 50);
        this.nextAvailableAt = new AtomicLong(System.currentTimeMillis());
    }

    /**
     * Non-blocking, lock-free rate limiter using CAS.
     * Each caller atomically reserves its own time slot, so 20 threads
     * can each get a slot without serializing on a synchronized lock.
     */
    public void acquire() throws InterruptedException {
        while (true) {
            long now = System.currentTimeMillis();
            long current = nextAvailableAt.get();
            long mySlot = Math.max(now, current);
            long nextSlot = mySlot + minIntervalMs;

            if (nextAvailableAt.compareAndSet(current, nextSlot)) {
                long waitMs = mySlot - now;
                if (waitMs > 0) {
                    Thread.sleep(waitMs);
                }
                return;
            }
            // CAS failed — another thread grabbed the slot; retry
        }
    }
}
