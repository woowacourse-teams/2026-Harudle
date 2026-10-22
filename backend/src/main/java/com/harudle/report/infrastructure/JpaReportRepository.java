package com.harudle.report.infrastructure;

import com.harudle.report.domain.ReportReason;
import com.harudle.report.domain.ReportTargetType;
import com.harudle.report.repository.ReportRepository;
import com.harudle.report.repository.ReportSnapshot;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaReportRepository implements ReportRepository {

    private static final String ACTIVE_MEMBER_QUERY = """
            SELECT count(*) FROM users u
            WHERE u.id = :userId AND u.deleted_at IS NULL
              AND EXISTS (SELECT 1 FROM oauth_accounts a WHERE a.user_id = u.id)
            """;
    private static final String FIND_QUERY = """
            SELECT id, reporter_id, target_type, target_id, target_user_id, reason, description, created_at
            FROM reports
            WHERE reporter_id = :reporterId AND target_type = :targetType AND target_id = :targetId
            """;
    private static final String INSERT_QUERY = """
            INSERT INTO reports (id, reporter_id, target_type, target_id, target_user_id,
                                 reason, description, status, created_at)
            VALUES (:id, :reporterId, :targetType, :targetId, :targetUserId,
                    :reason, :description, 'RECEIVED', :createdAt)
            ON CONFLICT (reporter_id, target_type, target_id) DO NOTHING
            """;

    private final EntityManager entityManager;

    JpaReportRepository(EntityManager entityManager) {
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
    public Optional<ReportSnapshot> findByReporterAndTarget(
            UUID reporterId, ReportTargetType targetType, UUID targetId
    ) {
        Objects.requireNonNull(reporterId, "reporterId는 필수입니다.");
        Objects.requireNonNull(targetType, "targetType은 필수입니다.");
        Objects.requireNonNull(targetId, "targetId는 필수입니다.");
        List<Object[]> rows = entityManager.createNativeQuery(FIND_QUERY)
                .setParameter("reporterId", reporterId).setParameter("targetType", targetType.name())
                .setParameter("targetId", targetId).getResultList();
        return rows.stream().map(JpaReportRepository::snapshot).findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean insert(ReportSnapshot report) {
        Objects.requireNonNull(report, "report는 필수입니다.");
        return entityManager.createNativeQuery(INSERT_QUERY)
                .setParameter("id", report.id()).setParameter("reporterId", report.reporterId())
                .setParameter("targetType", report.targetType().name()).setParameter("targetId", report.targetId())
                .setParameter("targetUserId", report.targetUserId()).setParameter("reason", report.reason().name())
                .setParameter("description", report.description()).setParameter("createdAt", report.createdAt())
                .executeUpdate() == 1;
    }

    private static ReportSnapshot snapshot(Object[] row) {
        return new ReportSnapshot((UUID) row[0], (UUID) row[1], ReportTargetType.valueOf((String) row[2]),
                (UUID) row[3], (UUID) row[4], ReportReason.valueOf((String) row[5]), (String) row[6], instant(row[7]));
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
        throw new IllegalStateException("신고 생성 시각의 DB 형식을 확인할 수 없습니다.");
    }
}
