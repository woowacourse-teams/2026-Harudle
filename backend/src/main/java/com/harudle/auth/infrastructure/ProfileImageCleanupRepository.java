package com.harudle.auth.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProfileImageCleanupRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProfileImageCleanupRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void add(UUID userId, String objectKey, Instant now) {
        add(userId, objectKey, now, now);
    }

    public void add(UUID userId, String objectKey, Instant now, Instant nextAttemptAt) {
        jdbcTemplate.update("""
                INSERT INTO profile_image_cleanup_tasks (object_key, user_id, created_at, next_attempt_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (object_key) DO NOTHING
                """, objectKey, userId, Timestamp.from(now), Timestamp.from(nextAttemptAt));
    }

    public List<PendingCleanup> findPending(Instant now, int limit) {
        return jdbcTemplate.query("""
                SELECT user_id, object_key
                FROM profile_image_cleanup_tasks
                WHERE next_attempt_at <= ?
                ORDER BY next_attempt_at, object_key
                LIMIT ?
                """, (row, index) -> new PendingCleanup(
                row.getObject("user_id", UUID.class), row.getString("object_key")
        ), Timestamp.from(now), limit);
    }

    public void defer(String objectKey, Instant nextAttemptAt) {
        jdbcTemplate.update("""
                UPDATE profile_image_cleanup_tasks SET next_attempt_at = ? WHERE object_key = ?
                """, Timestamp.from(nextAttemptAt), objectKey);
    }

    public void remove(String objectKey) {
        jdbcTemplate.update("DELETE FROM profile_image_cleanup_tasks WHERE object_key = ?", objectKey);
    }

    public record PendingCleanup(UUID userId, String objectKey) {
    }
}
