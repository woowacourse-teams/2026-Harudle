package com.harudle.moderation.infrastructure;

import com.harudle.auth.application.port.AccountRestrictionReader;
import com.harudle.moderation.domain.ModerationEvent;
import com.harudle.moderation.domain.ModerationState;
import com.harudle.moderation.domain.ModerationTargetType;
import com.harudle.moderation.repository.ModerationRepository;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaModerationRepository implements ModerationRepository, AccountRestrictionReader {
    private static final String STATE_COLUMNS = "target_type, target_id, restricted, version, reason, actor_id, changed_at";
    private final EntityManager entityManager;

    public JpaModerationRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public boolean isSuspendedForRequest(UUID userId) {
        return hasUserSuspension(userId);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean isSuspended(UUID userId) {
        // 발급 트랜잭션에서는 제재의 user FOR UPDATE와 반대 순서로 token을 잡지 않도록
        // 사용자의 공유 gate를 먼저 획득하고 발급 커밋까지 유지한다.
        entityManager.createNativeQuery("SELECT id FROM users WHERE id = :id FOR KEY SHARE")
                .setParameter("id", userId).getResultList();
        return hasUserSuspension(userId);
    }

    private boolean hasUserSuspension(UUID userId) {
        return ((Number) entityManager.createNativeQuery("""
                SELECT count(*) FROM moderation_states
                WHERE target_type = 'USER' AND target_id = :id AND restricted
                """).setParameter("id", userId).getSingleResult()).longValue() > 0;
    }

    @Override
    public boolean isActiveAdmin(UUID actorId) {
        return ((Number) entityManager.createNativeQuery("""
                SELECT count(*) FROM users u WHERE u.id = :id AND u.deleted_at IS NULL AND u.role = 'ADMIN'
                  AND NOT EXISTS (SELECT 1 FROM moderation_states m
                    WHERE m.target_type = 'USER' AND m.target_id = u.id AND m.restricted)
                """).setParameter("id", actorId).getSingleResult()).longValue() > 0;
    }

    @Override
    public boolean targetExists(ModerationTargetType type, UUID id) {
        return !entityManager.createNativeQuery("SELECT id FROM " + table(type) + " WHERE id = :id", UUID.class)
                .setParameter("id", id).setMaxResults(1).getResultList().isEmpty();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockManageableTarget(ModerationTargetType type, UUID id) {
        // 같은 일기의 재게시와 다른 피드 ID 접촉도 공통 부모부터 직렬화한다.
        if (type != ModerationTargetType.USER) {
            String diaryQuery = type == ModerationTargetType.FEED
                    ? "SELECT diary_id FROM feeds WHERE id = :id"
                    : "SELECT f.diary_id FROM comments c JOIN feeds f ON f.id = c.feed_id WHERE c.id = :id";
            List<?> diaries = entityManager.createNativeQuery(diaryQuery, UUID.class)
                    .setParameter("id", id).getResultList();
            if (diaries.isEmpty() || entityManager.createNativeQuery("SELECT id FROM diaries WHERE id = :id FOR UPDATE")
                    .setParameter("id", diaries.getFirst()).getResultList().isEmpty()) return false;
        }
        if (type == ModerationTargetType.COMMENT) {
            List<?> parents = entityManager.createNativeQuery("SELECT feed_id FROM comments WHERE id = :id", UUID.class)
                    .setParameter("id", id).getResultList();
            if (parents.isEmpty() || entityManager.createNativeQuery("SELECT id FROM feeds WHERE id = :id FOR UPDATE")
                    .setParameter("id", parents.getFirst()).getResultList().isEmpty()) return false;
        }
        String conditions = type == ModerationTargetType.USER
                ? " AND deleted_at IS NULL AND role <> 'ADMIN' AND EXISTS (SELECT 1 FROM oauth_accounts a WHERE a.user_id = users.id)"
                : "";
        return !entityManager.createNativeQuery("SELECT id FROM " + table(type)
                        + " WHERE id = :id" + conditions + " FOR UPDATE", UUID.class)
                .setParameter("id", id).getResultList().isEmpty();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<ModerationState> find(ModerationTargetType type, UUID id) {
        List<Object[]> rows = entityManager.createNativeQuery("SELECT " + STATE_COLUMNS
                        + " FROM moderation_states WHERE target_type = :type AND target_id = :id")
                .setParameter("type", type.name()).setParameter("id", id).getResultList();
        return rows.stream().map(JpaModerationRepository::state).findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    @SuppressWarnings("unchecked")
    public ModerationState lockOrCreate(ModerationTargetType type, UUID id) {
        entityManager.createNativeQuery("""
                INSERT INTO moderation_states (target_type,target_id) VALUES (:type,:id)
                ON CONFLICT (target_type,target_id) DO NOTHING
                """).setParameter("type", type.name()).setParameter("id", id).executeUpdate();
        List<Object[]> rows = entityManager.createNativeQuery("SELECT " + STATE_COLUMNS
                        + " FROM moderation_states WHERE target_type = :type AND target_id = :id FOR UPDATE")
                .setParameter("type", type.name()).setParameter("id", id).getResultList();
        return state(rows.getFirst());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void saveChange(ModerationState state) {
        int changed = entityManager.createNativeQuery("""
                UPDATE moderation_states SET restricted=:restricted,version=:version,reason=:reason,
                    actor_id=:actor,changed_at=:changedAt
                WHERE target_type=:type AND target_id=:id AND version=:previousVersion
                """).setParameter("restricted", state.restricted()).setParameter("version", state.version())
                .setParameter("reason", state.reason()).setParameter("actor", state.actorId())
                .setParameter("changedAt", state.changedAt()).setParameter("type", state.targetType().name())
                .setParameter("id", state.targetId()).setParameter("previousVersion", state.version() - 1).executeUpdate();
        if (changed != 1) throw new IllegalStateException("잠근 조치 상태가 변경되었습니다.");
        entityManager.createNativeQuery("""
                INSERT INTO moderation_events(id,target_type,target_id,restricted,version,reason,actor_id,changed_at)
                VALUES (:eventId,:type,:id,:restricted,:version,:reason,:actor,:changedAt)
                """).setParameter("eventId", UUID.randomUUID()).setParameter("type", state.targetType().name())
                .setParameter("id", state.targetId()).setParameter("restricted", state.restricted())
                .setParameter("version", state.version()).setParameter("reason", state.reason())
                .setParameter("actor", state.actorId()).setParameter("changedAt", state.changedAt()).executeUpdate();
        if (state.targetType() == ModerationTargetType.USER && state.restricted()) {
            entityManager.createNativeQuery("""
                    UPDATE refresh_tokens SET revoked_at = :now WHERE user_id = :id AND revoked_at IS NULL
                    """).setParameter("now", state.changedAt()).setParameter("id", state.targetId()).executeUpdate();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ModerationEvent> history(ModerationTargetType type, UUID id, int offset, int limit) {
        List<Object[]> rows = entityManager.createNativeQuery("SELECT id," + STATE_COLUMNS
                        + " FROM moderation_events WHERE target_type=:type AND target_id=:id ORDER BY version DESC")
                .setParameter("type", type.name()).setParameter("id", id).setFirstResult(offset).setMaxResults(limit)
                .getResultList();
        return rows.stream().map(row -> new ModerationEvent((UUID) row[0], ModerationTargetType.valueOf((String) row[1]),
                (UUID) row[2], (Boolean) row[3], ((Number) row[4]).longValue(), (String) row[5],
                (UUID) row[6], instant(row[7]))).toList();
    }

    @Override
    public long countHistory(ModerationTargetType type, UUID id) {
        return ((Number) entityManager.createNativeQuery("""
                SELECT count(*) FROM moderation_events WHERE target_type=:type AND target_id=:id
                """).setParameter("type", type.name()).setParameter("id", id).getSingleResult()).longValue();
    }

    private static ModerationState state(Object[] row) {
        return new ModerationState(ModerationTargetType.valueOf((String) row[0]), (UUID) row[1], (Boolean) row[2],
                ((Number) row[3]).longValue(), (String) row[4], (UUID) row[5], row[6] == null ? null : instant(row[6]));
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime dateTime) return dateTime.toInstant();
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        throw new IllegalStateException("조치 시각의 DB 형식을 확인할 수 없습니다.");
    }

    private static String table(ModerationTargetType type) {
        return switch (type) { case USER -> "users"; case FEED -> "feeds"; case COMMENT -> "comments"; };
    }
}
