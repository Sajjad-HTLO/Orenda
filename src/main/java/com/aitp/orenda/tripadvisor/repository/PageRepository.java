package com.aitp.orenda.tripadvisor.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
@ConditionalOnProperty(name = "tripadvisor.crawler.enabled", havingValue = "true")
public class PageRepository {

    private final JdbcTemplate jdbcTemplate;

    public PageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Integer resumableCompletedHotelCount(int offset) {
        Integer hotelCount = jdbcTemplate.query(
                "SELECT hotel_count FROM tripadvisor_crawled_pages WHERE page_offset = ? AND status = 'COMPLETED'",
                rs -> rs.next() ? rs.getInt("hotel_count") : null,
                offset);
        boolean shouldSkip = hotelCount != null && hotelCount > 0;
        if (hotelCount != null && hotelCount == 0) {
            log.warn("Tripadvisor progress lookup found completed page with 0 hotels; it will be retried. offset={}", offset);
            return null;
        }
        log.info("Tripadvisor progress lookup. offset={}, completed={}, hotelCount={}, shouldSkip={}",
                offset, hotelCount != null, hotelCount, shouldSkip);
        return hotelCount;
    }

    public void markInProgress(int offset, String url) {
        log.info("Tripadvisor progress update: IN_PROGRESS. offset={}, url={}", offset, url);
        jdbcTemplate.update("""
                INSERT INTO tripadvisor_crawled_pages(page_offset, url, status, attempt_count, updated_at)
                VALUES (?, ?, 'IN_PROGRESS', 1, NOW())
                ON CONFLICT(page_offset) DO UPDATE SET
                    url = EXCLUDED.url,
                    status = 'IN_PROGRESS',
                    attempt_count = tripadvisor_crawled_pages.attempt_count + 1,
                    updated_at = NOW()
                """, offset, url);
    }

    public void markCompleted(int offset, String url, int hotelCount) {
        log.info("Tripadvisor progress update: COMPLETED. offset={}, hotelCount={}, url={}", offset, hotelCount, url);
        jdbcTemplate.update("""
                INSERT INTO tripadvisor_crawled_pages(page_offset, url, status, hotel_count, attempt_count, crawled_at, updated_at, last_error)
                VALUES (?, ?, 'COMPLETED', ?, 1, NOW(), NOW(), NULL)
                ON CONFLICT(page_offset) DO UPDATE SET
                    url = EXCLUDED.url,
                    status = 'COMPLETED',
                    hotel_count = EXCLUDED.hotel_count,
                    crawled_at = NOW(),
                    updated_at = NOW(),
                    last_error = NULL
                """, offset, url, hotelCount);
    }

    public void markFailed(int offset, String url, Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        jdbcTemplate.update("""
                INSERT INTO tripadvisor_crawled_pages(page_offset, url, status, attempt_count, last_error, updated_at)
                VALUES (?, ?, 'FAILED', 1, ?, NOW())
                ON CONFLICT(page_offset) DO UPDATE SET
                    url = EXCLUDED.url,
                    status = 'FAILED',
                    last_error = EXCLUDED.last_error,
                    updated_at = NOW()
                """, offset, url, message);
        log.warn("Marked Tripadvisor listing page offset {} as failed: {}", offset, message);
    }
}
