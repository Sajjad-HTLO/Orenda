package com.aitp.orenda.review;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class PoiReviewRepository {

    private final JdbcTemplate jdbc;

    @Transactional
    public PoiReview insert(PoiReview review) {
        java.util.Map<String, Object> row = jdbc.queryForMap("""
                        INSERT INTO poi_review (poi_id, user_id, traveler_name, rating, title, comment)
                        VALUES (?::uuid, ?, ?, ?, ?, ?)
                        RETURNING id, created_at
                        """,
                review.poiId(), review.userId(), review.travelerName(),
                review.rating(), review.title(), review.comment());
        return new PoiReview(
                UUID.fromString(row.get("id").toString()),
                review.poiId(),
                review.userId(),
                review.travelerName(),
                review.rating(),
                review.title(),
                review.comment(),
                toInstant(row.get("created_at")));
    }

    public List<PoiReview> findByPoi(UUID poiId) {
        return jdbc.query("""
                        SELECT id, poi_id, user_id, traveler_name, rating, title, comment, created_at
                        FROM poi_review
                        WHERE poi_id = ?::uuid
                        ORDER BY created_at DESC
                        """,
                (rs, rowNum) -> new PoiReview(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("poi_id")),
                        rs.getString("user_id") == null ? null : UUID.fromString(rs.getString("user_id")),
                        rs.getString("traveler_name"),
                        rs.getInt("rating"),
                        rs.getString("title"),
                        rs.getString("comment"),
                        toInstant(rs.getObject("created_at", OffsetDateTime.class))),
                poiId);
    }

    public boolean poiExists(UUID poiId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM poi WHERE id = ?", Integer.class, poiId);
        return count != null && count > 0;
    }

    private static Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof OffsetDateTime odt) return odt.toInstant();
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        return null;
    }
}