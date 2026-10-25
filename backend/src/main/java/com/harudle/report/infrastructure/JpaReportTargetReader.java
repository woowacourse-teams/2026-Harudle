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
            SELECT target_user.id FROM users target_user
            WHERE target_user.id = :targetId AND target_user.deleted_at IS NULL
              AND EXISTS (SELECT 1 FROM oauth_accounts a WHERE a.user_id = target_user.id)
            """;
    private static final String FEED_QUERY = """
            SELECT feed_author.id FROM feeds f
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
            SELECT comment_author.id FROM comments c
            JOIN users comment_author ON comment_author.id = c.author_id
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

    private final EntityManager entityManager;
    private final DevTestDataVisibility visibility;

    JpaReportTargetReader(EntityManager entityManager, DevTestDataVisibility visibility) {
        this.entityManager = entityManager;
        this.visibility = visibility;
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Optional<UUID> findVisibleTargetAuthorId(UUID actorId, ReportTargetType targetType, UUID targetId) {
        Objects.requireNonNull(actorId, "actorId는 필수입니다.");
        Objects.requireNonNull(targetType, "targetType은 필수입니다.");
        Objects.requireNonNull(targetId, "targetId는 필수입니다.");
        Query query = entityManager.createNativeQuery(queryFor(targetType)).setParameter("targetId", targetId);
        if (visibility.enabled()) {
            query.setParameter("viewerId", actorId);
        }
        List<UUID> ids = query.getResultList();
        return ids.stream().findFirst();
    }

    private String queryFor(ReportTargetType targetType) {
        String query = switch (targetType) {
            case USER -> USER_QUERY;
            case FEED -> FEED_QUERY;
            case COMMENT -> COMMENT_QUERY;
        };
        if (!visibility.enabled()) {
            return query;
        }
        return query + switch (targetType) {
            case USER -> sameVisibilityPartition("target_user");
            case FEED -> sameVisibilityPartition("feed_author");
            case COMMENT -> sameVisibilityPartition("feed_author") + sameVisibilityPartition("comment_author");
        };
    }

    private static String sameVisibilityPartition(String author) {
        return " AND CASE WHEN " + author + ".primary_email LIKE '%.loadtest.invalid' THEN TRUE ELSE FALSE END"
                + " = CASE WHEN " + DevTestDataVisibility.TEST_VIEWER_SQL + " THEN TRUE ELSE FALSE END";
    }
}
