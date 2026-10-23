package com.harudle.push.infrastructure;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JdbcPushDeliveryQueue {

    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final Duration DEADLINE = Duration.ofHours(24);
    private static final String CLAIM = """
            WITH candidate AS (
                SELECT o.id, f.published_at FROM notification_outbox o
                JOIN feeds f ON f.id = o.feed_id
                WHERE ((o.status = 'PENDING' AND o.available_at <= ?)
                    OR (o.status = 'PROCESSING' AND o.locked_until <= ?))
                  AND f.published_at > ?
                ORDER BY o.available_at, o.id LIMIT 1 FOR UPDATE OF o SKIP LOCKED
            )
            UPDATE notification_outbox o
            SET status = 'PROCESSING', lock_token = ?, locked_until = ?,
                attempt_count = o.attempt_count + 1, updated_at = ?
            FROM candidate WHERE o.id = candidate.id
            RETURNING o.id, o.event_id, o.feed_id, o.push_registration_id,
                o.lock_token, o.locked_until, o.attempt_count, candidate.published_at
            """;
    private static final String TARGET = """
            SELECT r.recipient_value, r.version FROM notification_outbox o
            JOIN push_registrations r ON r.id = o.push_registration_id
                AND r.user_id = o.recipient_user_id AND r.revoked_at IS NULL
            JOIN auth_sessions s ON s.id = r.session_id AND s.user_id = r.user_id
                AND s.revoked_at IS NULL
            JOIN users u ON u.id = r.user_id AND u.deleted_at IS NULL
            JOIN feeds f ON f.id = o.feed_id AND f.deleted_at IS NULL
            WHERE o.id = ? AND o.status = 'PROCESSING' AND o.lock_token = ?
              AND o.locked_until > ?
              AND EXISTS (SELECT 1 FROM refresh_tokens rt
                  WHERE rt.session_id = s.id AND rt.user_id = s.user_id
                    AND rt.revoked_at IS NULL AND rt.expires_at > ?)
            """;
    private static final String EXPIRE = """
            UPDATE notification_outbox o SET status = 'FAILED', lock_token = NULL,
                locked_until = NULL, last_error_code = 'RETRY_DEADLINE', updated_at = ?
            FROM feeds f WHERE o.feed_id = f.id AND f.published_at <= ?
                AND (o.status = 'PENDING' OR (o.status = 'PROCESSING' AND o.locked_until <= ?))
            """;
    private static final String SENT = """
            UPDATE notification_outbox SET status = 'SENT', sent_at = ?, lock_token = NULL,
                locked_until = NULL, last_error_code = NULL, updated_at = ?
            WHERE id = ? AND status = 'PROCESSING' AND lock_token = ?
            """;
    private static final String RETRY = """
            UPDATE notification_outbox SET status = 'PENDING', available_at = ?,
                lock_token = NULL, locked_until = NULL, last_error_code = ?, updated_at = ?
            WHERE id = ? AND status = 'PROCESSING' AND lock_token = ?
            """;
    private static final String STOP = """
            UPDATE notification_outbox SET status = ?, lock_token = NULL,
                locked_until = NULL, last_error_code = ?, updated_at = ?
            WHERE id = ? AND status = 'PROCESSING' AND lock_token = ?
            """;
    private static final String REVOKE_UNREGISTERED = """
            UPDATE push_registrations SET revoked_at = ?, updated_at = ?
            WHERE id = ? AND recipient_value = ? AND version = ? AND revoked_at IS NULL
            """;
    private static final String CANCEL_REGISTRATION = """
            UPDATE notification_outbox SET status = 'CANCELLED', lock_token = NULL,
                locked_until = NULL, updated_at = ?
            WHERE push_registration_id = ? AND status IN ('PENDING', 'PROCESSING')
            """;

    private final JdbcTemplate jdbc;

    JdbcPushDeliveryQueue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Optional<Claim> claim(Instant now) {
        UUID token = UUID.randomUUID();
        return jdbc.query(CLAIM, (rs, row) -> new Claim(
                        rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
                        rs.getObject("feed_id", UUID.class), rs.getObject("push_registration_id", UUID.class),
                        rs.getObject("lock_token", UUID.class),
                        rs.getObject("locked_until", OffsetDateTime.class).toInstant(), rs.getInt("attempt_count"),
                        rs.getObject("published_at", OffsetDateTime.class).toInstant()),
                time(now), time(now), time(now.minus(DEADLINE)), token,
                time(now.plus(LEASE)), time(now)).stream().findFirst();
    }

    @Transactional
    public int expire(Instant now) {
        return jdbc.update(EXPIRE, time(now), time(now.minus(DEADLINE)), time(now));
    }

    @Transactional(readOnly = true)
    public Optional<Target> target(Claim claim, Instant now) {
        return jdbc.query(TARGET, (rs, row) -> new Target(
                        rs.getString("recipient_value"), rs.getLong("version")),
                claim.id(), claim.lockToken(), time(now), time(now)).stream().findFirst();
    }

    @Transactional
    public boolean sent(Claim claim, Instant now) {
        return jdbc.update(SENT, time(now), time(now), claim.id(), claim.lockToken()) == 1;
    }

    @Transactional
    public boolean retry(Claim claim, Instant availableAt, String errorCode, Instant now) {
        return jdbc.update(RETRY, time(availableAt), errorCode, time(now),
                claim.id(), claim.lockToken()) == 1;
    }

    @Transactional
    public boolean fail(Claim claim, String errorCode, Instant now) {
        return stop(claim, "FAILED", errorCode, now);
    }

    @Transactional
    public boolean cancel(Claim claim, Instant now) {
        return stop(claim, "CANCELLED", null, now);
    }

    @Transactional
    public boolean revokeUnregistered(Claim claim, Target target, Instant now) {
        int revoked = jdbc.update(REVOKE_UNREGISTERED, time(now), time(now),
                claim.registrationId(), target.fid(), target.version());
        if (revoked == 0) {
            return false;
        }
        jdbc.update(CANCEL_REGISTRATION, time(now), claim.registrationId());
        return true;
    }

    private boolean stop(Claim claim, String status, String errorCode, Instant now) {
        return jdbc.update(STOP, status, errorCode, time(now), claim.id(), claim.lockToken()) == 1;
    }

    private Timestamp time(Instant instant) {
        return Timestamp.from(instant);
    }

    record Claim(UUID id, UUID eventId, UUID feedId, UUID registrationId,
                 UUID lockToken, Instant lockedUntil, int attemptCount, Instant publishedAt) { }

    record Target(String fid, long version) { }
}
