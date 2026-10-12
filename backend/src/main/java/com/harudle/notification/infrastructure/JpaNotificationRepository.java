package com.harudle.notification.infrastructure;

import com.harudle.notification.query.NotificationCursor;
import com.harudle.notification.repository.NotificationReadTarget;
import com.harudle.notification.repository.NotificationRepository;
import com.harudle.notification.repository.NotificationSnapshot;
import com.harudle.notification.service.port.InAppNotificationRecorder.Type;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaNotificationRepository implements NotificationRepository {
    private static final String ACTIVE_MEMBER_QUERY = """
            SELECT count(*)
            FROM users u
            WHERE u.id = :userId AND u.deleted_at IS NULL
              AND EXISTS (SELECT 1 FROM oauth_accounts a WHERE a.user_id = u.id)
            """;
    private static final String VISIBLE_PARENT_JOIN = """
            JOIN feeds f ON f.id = n.feed_id
            JOIN diaries d ON d.id = f.diary_id
            """;
    private static final String VISIBLE_PARENT_CONDITION = " f.deleted_at IS NULL AND d.deleted_at IS NULL ";
    // Keep historical rows, but show a LIKE only while its exact source like is active.
    // COMMENT history does not depend on whether the original comment still exists.
    private static final String VISIBLE_ACTION_CONDITION = """
              AND (n.type = 'COMMENT' OR EXISTS (
                  SELECT 1 FROM feed_likes l
                  WHERE l.id = n.source_action_id
                    AND l.feed_id = n.feed_id AND l.user_id = n.actor_user_id
              ))
            """;
    private static final String LIST_QUERY = """
            SELECT n.id, n.recipient_user_id, a.id, n.feed_id, n.type, n.created_at, n.read_at
            FROM notifications n
            """ + VISIBLE_PARENT_JOIN + """
            LEFT JOIN users a ON a.id = n.actor_user_id AND a.deleted_at IS NULL
            WHERE n.recipient_user_id = :recipientId AND
            """ + VISIBLE_PARENT_CONDITION + VISIBLE_ACTION_CONDITION;
    private static final String AFTER_PREDICATE = """
              AND (n.created_at < :afterCreatedAt
                   OR (n.created_at = :afterCreatedAt AND n.id < :afterId))
            """;
    private static final String ORDER_QUERY = " ORDER BY n.created_at DESC, n.id DESC";
    private static final String COUNT_UNREAD_QUERY = """
            SELECT count(*)
            FROM notifications n
            """ + VISIBLE_PARENT_JOIN + """
            WHERE n.recipient_user_id = :recipientId AND n.read_at IS NULL AND
            """ + VISIBLE_PARENT_CONDITION + VISIBLE_ACTION_CONDITION;
    private static final String FIND_VISIBLE_QUERY = """
            SELECT n.id, n.recipient_user_id
            FROM notifications n
            """ + VISIBLE_PARENT_JOIN + """
            WHERE n.id = :notificationId AND
            """ + VISIBLE_PARENT_CONDITION + VISIBLE_ACTION_CONDITION;
    private static final String MARK_READ_QUERY = """
            UPDATE notifications n
            SET read_at = COALESCE(n.read_at, :readAt)
            WHERE n.id = :notificationId AND n.recipient_user_id = :recipientId
              AND EXISTS (
                  SELECT 1 FROM feeds f JOIN diaries d ON d.id = f.diary_id
                  WHERE f.id = n.feed_id AND f.deleted_at IS NULL AND d.deleted_at IS NULL
              )
            """ + VISIBLE_ACTION_CONDITION;

    private final EntityManager entityManager;

    JpaNotificationRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActiveMember(UUID userId) {
        Objects.requireNonNull(userId, "userId는 필수입니다.");
        return ((Number) entityManager.createNativeQuery(ACTIVE_MEMBER_QUERY)
                .setParameter("userId", userId).getSingleResult()).longValue() > 0;
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<NotificationSnapshot> findAfter(UUID recipientId, Optional<NotificationCursor> cursor, int limit) {
        Objects.requireNonNull(recipientId, "recipientId는 필수입니다.");
        Objects.requireNonNull(cursor, "cursor는 필수입니다.");
        if (limit < 1 || limit > 51 || cursor.filter(value -> !recipientId.equals(value.recipientId())).isPresent()) {
            throw new IllegalArgumentException("알림 조회 조건이 올바르지 않습니다.");
        }
        Query query = entityManager.createNativeQuery(
                LIST_QUERY + (cursor.isPresent() ? AFTER_PREDICATE : "") + ORDER_QUERY
        ).setParameter("recipientId", recipientId).setMaxResults(limit);
        cursor.ifPresent(value -> query.setParameter("afterCreatedAt", value.createdAt())
                .setParameter("afterId", value.id()));
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(JpaNotificationRepository::snapshot).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countUnread(UUID recipientId) {
        Objects.requireNonNull(recipientId, "recipientId는 필수입니다.");
        return ((Number) entityManager.createNativeQuery(COUNT_UNREAD_QUERY)
                .setParameter("recipientId", recipientId).getSingleResult()).longValue();
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Optional<NotificationReadTarget> findVisibleById(UUID notificationId) {
        Objects.requireNonNull(notificationId, "notificationId는 필수입니다.");
        List<Object[]> rows = entityManager.createNativeQuery(FIND_VISIBLE_QUERY)
                .setParameter("notificationId", notificationId).getResultList();
        return rows.stream().map(row -> new NotificationReadTarget((UUID) row[0], (UUID) row[1])).findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markRead(UUID notificationId, UUID recipientId, Instant readAt) {
        Objects.requireNonNull(notificationId, "notificationId는 필수입니다.");
        Objects.requireNonNull(recipientId, "recipientId는 필수입니다.");
        Objects.requireNonNull(readAt, "readAt은 필수입니다.");
        return entityManager.createNativeQuery(MARK_READ_QUERY)
                .setParameter("notificationId", notificationId).setParameter("recipientId", recipientId)
                .setParameter("readAt", readAt).executeUpdate() == 1;
    }

    private static NotificationSnapshot snapshot(Object[] row) {
        return new NotificationSnapshot((UUID) row[0], (UUID) row[1], (UUID) row[2], (UUID) row[3],
                Type.valueOf((String) row[4]), instant(row[5]), row[6] == null ? null : instant(row[6]));
    }

    private static Instant instant(@Nullable Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime dateTime) {
            return dateTime.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new IllegalStateException("알림 시각의 DB 형식을 확인할 수 없습니다.");
    }
}
