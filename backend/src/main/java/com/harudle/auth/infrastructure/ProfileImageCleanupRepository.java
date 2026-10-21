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

    public void add(UUID userId, String objectKey, Instant now, Instant deleteAfter) {
        jdbcTemplate.update("""
                INSERT INTO profile_image_cleanup_tasks (object_key, user_id, created_at, delete_after)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (object_key) DO NOTHING
                """, objectKey, userId, Timestamp.from(now), Timestamp.from(deleteAfter));
    }

    public List<PendingCleanup> findPending(UUID userId, Instant now, int limit) {
        return jdbcTemplate.query("""
                SELECT user_id, object_key
                FROM profile_image_cleanup_tasks
                WHERE user_id = ? AND delete_after <= ?
                ORDER BY delete_after, object_key
                LIMIT ?
                """, (row, index) -> new PendingCleanup(
                row.getObject("user_id", UUID.class), row.getString("object_key")
        ), userId, Timestamp.from(now), limit);
    }

    public void remove(String objectKey) {
        jdbcTemplate.update("DELETE FROM profile_image_cleanup_tasks WHERE object_key = ?", objectKey);
    }

    public record PendingCleanup(UUID userId, String objectKey) {
    }
}
