package com.harudle.block.infrastructure;

import com.harudle.block.domain.UserBlock;
import com.harudle.block.query.BlockCursor;
import com.harudle.block.repository.BlockRepository;
import com.harudle.block.repository.BlockSnapshot;
import com.harudle.common.testdata.DevTestDataVisibility;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaBlockRepository implements BlockRepository {
    private static final String ACTIVE_MEMBER = """
            SELECT count(*) FROM users u
            WHERE u.id = :userId AND u.deleted_at IS NULL
              AND EXISTS (SELECT 1 FROM oauth_accounts a WHERE a.user_id = u.id)
            """;
    private static final String ACTIVE_TARGET = """
            SELECT count(*) FROM users target_user
            WHERE target_user.id = :targetId AND target_user.deleted_at IS NULL
              AND EXISTS (SELECT 1 FROM oauth_accounts a WHERE a.user_id = target_user.id)
            """;
    private static final String SAME_PARTITION = """
              AND CASE WHEN target_user.primary_email LIKE '%.loadtest.invalid' THEN TRUE ELSE FALSE END
                = CASE WHEN
            """ + DevTestDataVisibility.TEST_VIEWER_SQL + " THEN TRUE ELSE FALSE END";
    private static final String BETWEEN = """
            SELECT count(*) FROM user_blocks
            WHERE (blocker_id = :first AND blocked_id = :second)
               OR (blocker_id = :second AND blocked_id = :first)
            """;
    private static final String INSERT = """
            INSERT INTO user_blocks (id, blocker_id, blocked_id, created_at)
            VALUES (:id, :blockerId, :blockedId, :createdAt)
            ON CONFLICT (blocker_id, blocked_id) DO NOTHING
            """;
    private static final String LIST = """
            SELECT b.id, b.blocked_id, b.created_at,
                   (u.deleted_at IS NOT NULL OR NOT EXISTS (
                       SELECT 1 FROM oauth_accounts a WHERE a.user_id = u.id)) AS inactive_target
            FROM user_blocks b JOIN users u ON u.id = b.blocked_id
            WHERE b.blocker_id = :blockerId
            """;
    private static final String AFTER = """
              AND (b.created_at < :createdAt OR (b.created_at = :createdAt AND b.id < :id))
            """;
    private static final String CANCEL_UNSENT = """
            UPDATE notification_outbox o
            SET status = 'CANCELLED', lock_token = NULL, locked_until = NULL,
                sent_at = NULL, updated_at = CURRENT_TIMESTAMP
            FROM feeds f JOIN diaries d ON d.id = f.diary_id
            WHERE o.feed_id = f.id AND o.status IN ('PENDING', 'PROCESSING')
              AND ((o.recipient_user_id = :first AND d.user_id = :second)
                OR (o.recipient_user_id = :second AND d.user_id = :first))
            """;

    private final EntityManager entityManager;
    private final DevTestDataVisibility visibility;

    JpaBlockRepository(EntityManager entityManager, DevTestDataVisibility visibility) {
        this.entityManager = entityManager;
        this.visibility = visibility;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActiveMember(UUID userId) {
        return ((Number) entityManager.createNativeQuery(ACTIVE_MEMBER)
                .setParameter("userId", userId).getSingleResult()).longValue() > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActiveTarget(UUID actorId, UUID targetId) {
        var query = entityManager.createNativeQuery(ACTIVE_TARGET + (visibility.enabled() ? SAME_PARTITION : ""))
                .setParameter("targetId", targetId);
        if (visibility.enabled()) {
            query.setParameter("viewerId", actorId);
        }
        return ((Number) query.getSingleResult()).longValue() > 0;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockPair(long key) {
        entityManager.createNativeQuery("SELECT 1 FROM pg_advisory_xact_lock(:key)")
                .setParameter("key", key).getSingleResult();
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public boolean existsBetween(UUID first, UUID second) {
        return ((Number) entityManager.createNativeQuery(BETWEEN).setParameter("first", first)
                .setParameter("second", second).getSingleResult()).longValue() > 0;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean insert(UserBlock block) {
        return entityManager.createNativeQuery(INSERT).setParameter("id", block.getId())
                .setParameter("blockerId", block.getBlockerId()).setParameter("blockedId", block.getBlockedId())
                .setParameter("createdAt", block.getCreatedAt()).executeUpdate() == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteOwn(UUID blockerId, UUID blockedId) {
        entityManager.createNativeQuery("DELETE FROM user_blocks WHERE blocker_id = :actor AND blocked_id = :target")
                .setParameter("actor", blockerId).setParameter("target", blockedId).executeUpdate();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelUnsentBetween(UUID first, UUID second) {
        entityManager.createNativeQuery(CANCEL_UNSENT).setParameter("first", first)
                .setParameter("second", second).executeUpdate();
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<BlockSnapshot> findAfter(UUID blockerId, Optional<BlockCursor> cursor, int limit) {
        if (limit < 1 || limit > 51 || cursor.filter(value -> !blockerId.equals(value.blockerId())).isPresent()) {
            throw new IllegalArgumentException("차단 목록 조회 조건이 올바르지 않습니다.");
        }
        var query = entityManager.createNativeQuery(LIST + (cursor.isPresent() ? AFTER : "")
                + " ORDER BY b.created_at DESC, b.id DESC").setParameter("blockerId", blockerId).setMaxResults(limit);
        cursor.ifPresent(value -> query.setParameter("createdAt", value.createdAt()).setParameter("id", value.id()));
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(row -> new BlockSnapshot((UUID) row[0], (UUID) row[1], instant(row[2]),
                (Boolean) row[3])).toList();
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime dateTime) {
            return dateTime.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new IllegalStateException("차단 시각의 DB 형식을 확인할 수 없습니다.");
    }
}
