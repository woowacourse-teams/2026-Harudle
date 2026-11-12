package com.harudle.report.infrastructure;

import com.harudle.common.testdata.DevTestDataVisibility;
import com.harudle.report.domain.ReportTargetType;
import com.harudle.report.repository.ReportTargetReader;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaReportTargetReader implements ReportTargetReader {

    private static final String USER_QUERY = """
            SELECT target_user.id, target_user.id FROM users target_user
            WHERE target_user.id = :targetId AND target_user.deleted_at IS NULL
              AND EXISTS (SELECT 1 FROM oauth_accounts a WHERE a.user_id = target_user.id)
            """;
    private static final String FEED_QUERY = """
            SELECT f.id, feed_author.id FROM feeds f
            JOIN diaries d ON d.id = f.diary_id
            JOIN users feed_author ON feed_author.id = d.user_id
            WHERE f.id = :targetId AND f.deleted_at IS NULL AND d.deleted_at IS NULL
              AND feed_author.deleted_at IS NULL
              AND NOT EXISTS (SELECT 1 FROM feeds hidden_feed
                  JOIN moderation_states moderation ON moderation.target_type = 'FEED'
                    AND moderation.target_id = hidden_feed.id AND moderation.restricted
                  WHERE hidden_feed.diary_id = f.diary_id)
            """;
    private static final String COMMENT_QUERY = """
            SELECT c.id, comment_author.id FROM comments c
            LEFT JOIN users comment_author ON comment_author.id = c.author_id
            JOIN feeds f ON f.id = c.feed_id
            JOIN diaries d ON d.id = f.diary_id
            JOIN users feed_author ON feed_author.id = d.user_id
            WHERE c.id = :targetId AND c.deleted_at IS NULL AND f.deleted_at IS NULL
              AND d.deleted_at IS NULL AND feed_author.deleted_at IS NULL
              AND NOT EXISTS (SELECT 1 FROM feeds hidden_feed
                  JOIN moderation_states moderation ON moderation.target_type = 'FEED'
                    AND moderation.target_id = hidden_feed.id AND moderation.restricted
                  WHERE hidden_feed.diary_id = f.diary_id)
              AND NOT EXISTS (SELECT 1 FROM moderation_states moderation
                  WHERE moderation.target_type = 'COMMENT' AND moderation.target_id = c.id AND moderation.restricted)
            """;
    private static final String DIARY_QUERY = """
            SELECT d.id, d.user_id FROM diaries d
            JOIN diary_generations generation ON generation.diary_id = d.id
            WHERE d.id = :targetId AND d.user_id = :actorId AND d.deleted_at IS NULL
              AND generation.status = 'SUCCEEDED'
            """;

    private final EntityManager entityManager;
    private final DevTestDataVisibility visibility;

    JpaReportTargetReader(EntityManager entityManager, DevTestDataVisibility visibility) {
        this.entityManager = entityManager;
        this.visibility = visibility;
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Optional<ReportTarget> findVisibleTarget(UUID actorId, ReportTargetType targetType, UUID targetId) {
        Objects.requireNonNull(actorId, "actorId는 필수입니다.");
        Objects.requireNonNull(targetType, "targetType은 필수입니다.");
        Objects.requireNonNull(targetId, "targetId는 필수입니다.");
        Query query = entityManager.createNativeQuery(queryFor(targetType)).setParameter("targetId", targetId);
        if (targetType == ReportTargetType.DIARY) {
            query.setParameter("actorId", actorId);
        } else if (visibility.enabled()) {
            query.setParameter("viewerId", actorId);
        }
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(row -> new ReportTarget((UUID) row[1])).findFirst();
    }

    private String queryFor(ReportTargetType targetType) {
        String query = switch (targetType) {
            case USER -> USER_QUERY;
            case FEED -> FEED_QUERY;
            case COMMENT -> COMMENT_QUERY;
            case DIARY -> DIARY_QUERY;
        };
        // 개인 일기는 소유자 본인만 조회하므로 열람자 그룹 분리가 필요 없다.
        if (!visibility.enabled() || targetType == ReportTargetType.DIARY) {
            return query;
        }
        return query + switch (targetType) {
            case USER -> sameVisibilityPartition("target_user");
            case FEED -> sameVisibilityPartition("feed_author");
            case COMMENT -> sameVisibilityPartition("feed_author")
                    + " AND (comment_author.id IS NULL OR "
                    + sameVisibilityPartitionCondition("comment_author") + ")";
            case DIARY -> throw new IllegalStateException("개인 일기 신고에는 열람자 그룹 조건을 붙이지 않습니다.");
        };
    }

    private static String sameVisibilityPartition(String author) {
        return " AND " + sameVisibilityPartitionCondition(author);
    }

    private static String sameVisibilityPartitionCondition(String author) {
        return "CASE WHEN " + author + ".primary_email LIKE '%.loadtest.invalid' THEN TRUE ELSE FALSE END"
                + " = CASE WHEN " + DevTestDataVisibility.TEST_VIEWER_SQL + " THEN TRUE ELSE FALSE END";
    }
}
