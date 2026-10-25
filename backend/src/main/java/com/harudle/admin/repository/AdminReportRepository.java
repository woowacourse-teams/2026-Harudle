package com.harudle.admin.repository;

import com.harudle.admin.query.AdminReportPage;
import com.harudle.admin.query.AdminReportSnapshot;
import com.harudle.admin.query.AdminReportStatus;
import com.harudle.admin.query.AdminReportSummary;
import com.harudle.admin.query.AdminReportTarget;
import com.harudle.admin.service.AdminReportReview;
import com.harudle.report.domain.ReportReason;
import com.harudle.report.domain.ReportTargetType;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AdminReportRepository {

    private static final String REPORT_COLUMNS = """
            id, reporter_id, target_type, target_id, target_user_id, reason, description, status, created_at,
            reviewed_by, reviewed_at, review_reason
            """;
    private static final String LIST_QUERY = """
            SELECT id, reporter_id, target_type, target_id, target_user_id, reason, status, created_at, reviewed_at
            FROM reports WHERE status = :status ORDER BY created_at DESC, id DESC
            """;
    private static final String USER_TARGET_QUERY = """
            SELECT u.id, u.nickname, u.deleted_at IS NOT NULL, u.deleted_at IS NOT NULL,
                   false, false, false,
                   u.deleted_at IS NULL AND EXISTS (SELECT 1 FROM oauth_accounts oa WHERE oa.user_id = u.id),
                   NULL, NULL, NULL, NULL
            FROM users u WHERE u.id = :targetId
            """;
    private static final String FEED_TARGET_QUERY = """
            SELECT d.user_id, u.nickname, u.deleted_at IS NOT NULL,
                   f.deleted_at IS NOT NULL, d.deleted_at IS NOT NULL,
                   COALESCE(state.restricted, false), related_state.restricted,
                   f.deleted_at IS NULL AND d.deleted_at IS NULL AND u.deleted_at IS NULL
                       AND generation.status = 'SUCCEEDED' AND NOT COALESCE(state.restricted, false)
                       AND NOT related_state.restricted,
                   f.id, generation.title, generation.image_object_key, NULL
            FROM feeds f
            JOIN diaries d ON d.id = f.diary_id
            JOIN users u ON u.id = d.user_id
            LEFT JOIN diary_generations generation ON generation.diary_id = d.id
            LEFT JOIN moderation_states state ON state.target_type = 'FEED' AND state.target_id = f.id
            CROSS JOIN LATERAL (
                SELECT EXISTS (
                    SELECT 1 FROM feeds related
                    JOIN moderation_states hidden ON hidden.target_type = 'FEED' AND hidden.target_id = related.id
                    WHERE related.diary_id = d.id AND related.id <> f.id AND hidden.restricted
                ) AS restricted
            ) related_state
            WHERE f.id = :targetId
            """;
    private static final String COMMENT_TARGET_QUERY = """
            SELECT u.id, u.nickname, u.deleted_at IS NOT NULL,
                   c.deleted_at IS NOT NULL, f.deleted_at IS NOT NULL OR d.deleted_at IS NOT NULL,
                   COALESCE(comment_state.restricted, false), feed_state.restricted,
                   c.deleted_at IS NULL AND f.deleted_at IS NULL AND d.deleted_at IS NULL
                       AND feed_author.deleted_at IS NULL
                       AND NOT COALESCE(comment_state.restricted, false)
                       AND NOT feed_state.restricted,
                   f.id, generation.title, generation.image_object_key, c.content
            FROM comments c
            JOIN users u ON u.id = c.author_id
            JOIN feeds f ON f.id = c.feed_id
            JOIN diaries d ON d.id = f.diary_id
            JOIN users feed_author ON feed_author.id = d.user_id
            LEFT JOIN diary_generations generation ON generation.diary_id = d.id
            LEFT JOIN moderation_states comment_state
                ON comment_state.target_type = 'COMMENT' AND comment_state.target_id = c.id
            CROSS JOIN LATERAL (
                SELECT EXISTS (
                    SELECT 1 FROM feeds related
                    JOIN moderation_states hidden ON hidden.target_type = 'FEED' AND hidden.target_id = related.id
                    WHERE related.diary_id = d.id AND hidden.restricted
                ) AS restricted
            ) feed_state
            WHERE c.id = :targetId
            """;

    private final EntityManager entityManager;

    public AdminReportRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public AdminReportPage search(AdminReportStatus status, Pageable pageable) {
        Objects.requireNonNull(status, "신고 상태는 필수입니다.");
        List<Object[]> rows = entityManager.createNativeQuery(LIST_QUERY)
                .setParameter("status", status.name())
                .setFirstResult(Math.toIntExact(pageable.getOffset()))
                .setMaxResults(pageable.getPageSize()).getResultList();
        long count = ((Number) entityManager.createNativeQuery("SELECT count(*) FROM reports WHERE status = :status")
                .setParameter("status", status.name()).getSingleResult()).longValue();
        return new AdminReportPage(rows.stream().map(AdminReportRepository::summary).toList(), count);
    }

    @Transactional(readOnly = true)
    public Optional<AdminReportSnapshot> findById(UUID reportId) {
        return find(reportId, false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AdminReportSnapshot> findByIdForUpdate(UUID reportId) {
        return find(reportId, true);
    }

    @SuppressWarnings("unchecked")
    private Optional<AdminReportSnapshot> find(UUID reportId, boolean forUpdate) {
        Objects.requireNonNull(reportId, "신고 ID는 필수입니다.");
        List<Object[]> rows = entityManager.createNativeQuery("SELECT " + REPORT_COLUMNS
                        + " FROM reports WHERE id = :reportId" + (forUpdate ? " FOR UPDATE" : ""))
                .setParameter("reportId", reportId).getResultList();
        return rows.stream().map(AdminReportRepository::snapshot).findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AdminReportSnapshot completeReview(UUID reportId, UUID reviewerId, AdminReportReview review, Instant at) {
        Object[] row = (Object[]) entityManager.createNativeQuery("""
                        UPDATE reports SET status = :status, reviewed_by = :reviewerId,
                            reviewed_at = :reviewedAt, review_reason = :reason
                        WHERE id = :reportId AND status = 'RECEIVED' RETURNING
                        """ + REPORT_COLUMNS)
                .setParameter("status", review.status().name()).setParameter("reviewerId", reviewerId)
                .setParameter("reviewedAt", at).setParameter("reason", review.reason())
                .setParameter("reportId", reportId).getSingleResult();
        return snapshot(row);
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public AdminReportTarget findTarget(ReportTargetType type, UUID targetId) {
        String query = switch (type) {
            case USER -> USER_TARGET_QUERY;
            case FEED -> FEED_TARGET_QUERY;
            case COMMENT -> COMMENT_TARGET_QUERY;
        };
        List<Object[]> rows = entityManager.createNativeQuery(query).setParameter("targetId", targetId).getResultList();
        return rows.stream().map(AdminReportRepository::target).findFirst().orElseGet(AdminReportTarget::missing);
    }

    private static AdminReportSummary summary(Object[] row) {
        return new AdminReportSummary((UUID) row[0], (UUID) row[1], ReportTargetType.valueOf((String) row[2]),
                (UUID) row[3], (UUID) row[4], ReportReason.valueOf((String) row[5]),
                AdminReportStatus.valueOf((String) row[6]), instant(row[7]), nullableInstant(row[8]));
    }

    private static AdminReportSnapshot snapshot(Object[] row) {
        return new AdminReportSnapshot((UUID) row[0], (UUID) row[1], ReportTargetType.valueOf((String) row[2]),
                (UUID) row[3], (UUID) row[4], ReportReason.valueOf((String) row[5]), (String) row[6],
                AdminReportStatus.valueOf((String) row[7]), instant(row[8]), (UUID) row[9],
                nullableInstant(row[10]), (String) row[11]);
    }

    private static AdminReportTarget target(Object[] row) {
        return new AdminReportTarget(true, (UUID) row[0], (String) row[1], Boolean.TRUE.equals(row[2]),
                Boolean.TRUE.equals(row[3]), Boolean.TRUE.equals(row[4]), Boolean.TRUE.equals(row[5]),
                Boolean.TRUE.equals(row[6]), Boolean.TRUE.equals(row[7]), (UUID) row[8],
                (String) row[9], (String) row[10], (String) row[11]);
    }

    private static @Nullable Instant nullableInstant(@Nullable Object value) {
        return value == null ? null : instant(value);
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant result) {
            return result;
        }
        if (value instanceof OffsetDateTime result) {
            return result.toInstant();
        }
        if (value instanceof Timestamp result) {
            return result.toInstant();
        }
        throw new IllegalStateException("신고 시각의 저장 형식이 올바르지 않습니다.");
    }
}
