package com.harudle.generation.diary.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads only active diaries so deleted user content does not produce availability alerts. */
@Repository
public class ImageIntegrityCandidateRepository {

    private static final String BASE_QUERY = """
            SELECT generation.id, generation.image_object_key
            FROM diary_generations generation
            JOIN diaries diary ON diary.id = generation.diary_id
            JOIN users owner ON owner.id = diary.user_id
            WHERE generation.status = 'SUCCEEDED'
              AND generation.image_object_key IS NOT NULL
              AND generation.completed_at <= ?
              AND diary.deleted_at IS NULL
              AND owner.deleted_at IS NULL
            """;
    private static final String FIRST_PAGE_QUERY = BASE_QUERY + " ORDER BY generation.id LIMIT ?";
    private static final String NEXT_PAGE_QUERY = BASE_QUERY
            + " AND generation.id > ? ORDER BY generation.id LIMIT ?";
    private static final String STILL_VISIBLE_QUERY = """
            SELECT EXISTS (
                SELECT 1
                FROM diary_generations generation
                JOIN diaries diary ON diary.id = generation.diary_id
                JOIN users owner ON owner.id = diary.user_id
                WHERE generation.id = ?
                  AND generation.image_object_key = ?
                  AND generation.status = 'SUCCEEDED'
                  AND generation.completed_at <= ?
                  AND diary.deleted_at IS NULL
                  AND owner.deleted_at IS NULL
            )
            """;

    private final JdbcTemplate jdbcTemplate;

    public ImageIntegrityCandidateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ImageIntegrityCandidate> findNext(UUID afterId, Instant completedBefore, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("점검 페이지 크기는 양수여야 합니다.");
        }
        if (afterId == null) {
            return jdbcTemplate.query(FIRST_PAGE_QUERY,
                    (resultSet, rowNumber) -> new ImageIntegrityCandidate(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getString("image_object_key")
                    ), Timestamp.from(completedBefore), limit);
        }
        return jdbcTemplate.query(NEXT_PAGE_QUERY,
                (resultSet, rowNumber) -> new ImageIntegrityCandidate(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("image_object_key")
                ), Timestamp.from(completedBefore), afterId, limit);
    }

    public boolean isStillVisible(ImageIntegrityCandidate candidate, Instant completedBefore) {
        Boolean visible = jdbcTemplate.queryForObject(STILL_VISIBLE_QUERY, Boolean.class,
                candidate.generationId(), candidate.imageObjectKey(), Timestamp.from(completedBefore));
        return Boolean.TRUE.equals(visible);
    }
}
