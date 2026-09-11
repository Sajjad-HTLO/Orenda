package com.aitp.orenda.tripadvisor.attractions;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persists crawled Tripadvisor traveler reviews (and any per-aspect sub-ratings)
 * onto the {@code poi_review_crawled} / {@code poi_review_aspect} tables. The
 * UNIQUE constraint on (poi_id, reviewer_name, review_date, comment) makes
 * re-crawls idempotent.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class CrawledReviewRepository {

    private final JdbcTemplate jdbc;

    @Transactional
    public int save(UUID poiId, List<CrawledPoiReview> reviews) {
        if (poiId == null || reviews == null || reviews.isEmpty()) {
            return 0;
        }
        int saved = 0;
        for (CrawledPoiReview review : reviews) {
            saved += insertReview(poiId, review);
        }
        log.info("Tripadvisor crawled reviews persisted. poiId={}, inputReviews={}, saved={}",
                poiId, reviews.size(), saved);
        return saved;
    }

    private int insertReview(UUID poiId, CrawledPoiReview review) {
        UUID reviewId;
        try {
            reviewId = jdbc.queryForObject("""
                    INSERT INTO poi_review_crawled
                        (poi_id, tripadvisor_id, reviewer_name, rating, title, comment, review_date, source_url)
                    VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (poi_id, reviewer_name, review_date, comment) DO NOTHING
                    RETURNING id
                    """, UUID.class,
                    poiId, review.tripadvisorId(), review.reviewerName(), review.rating(),
                    review.title(), review.comment(), review.reviewDate(), review.sourceUrl());
        } catch (EmptyResultDataAccessException e) {
            // Duplicate review already persisted from a previous crawl.
            return 0;
        }
        if (reviewId == null) {
            return 0;
        }
        return insertAspects(reviewId, review.aspects());
    }

    private int insertAspects(UUID reviewId, Map<String, Integer> aspects) {
        if (aspects == null || aspects.isEmpty()) {
            return 1;
        }
        int inserted = 0;
        for (Map.Entry<String, Integer> entry : aspects.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            int rows = jdbc.update("""
                    INSERT INTO poi_review_aspect (review_id, aspect, rating)
                    VALUES (?::uuid, ?, ?)
                    ON CONFLICT DO NOTHING
                    """, reviewId, entry.getKey(), entry.getValue());
            inserted += rows;
        }
        return 1;
    }
}
