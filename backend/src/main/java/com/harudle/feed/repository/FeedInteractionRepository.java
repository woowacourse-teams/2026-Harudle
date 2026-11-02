package com.harudle.feed.repository;

import com.harudle.common.testdata.DevTestDataVisibility;
import com.harudle.feed.domain.Feed;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class FeedInteractionRepository {
    private static final String ACTIVE_AUTHOR = """
            SELECT diary.userId FROM Diary diary, User author
            WHERE diary.id = :diaryId AND author.id = diary.userId
              AND diary.deletedAt IS NULL AND author.deletedAt IS NULL
            """;
    private final EntityManager entityManager;
    private final DevTestDataVisibility visibility;

    public FeedInteractionRepository(EntityManager entityManager, DevTestDataVisibility visibility) {
        this.entityManager = entityManager;
        this.visibility = visibility;
    }

    public Optional<Feed> findByIdForUpdate(UUID feedId) {
        // 관리자 숨김과 재게시도 일기 -> 피드 순서로 잠근다. 잠금 대기 전에 Feed를 관리 상태로 읽지 않는다.
        List<?> diaryIds = entityManager.createNativeQuery(
                "SELECT diary_id FROM feeds WHERE id = :feedId", UUID.class)
                .setParameter("feedId", feedId).getResultList();
        if (diaryIds.isEmpty()) {
            return Optional.empty();
        }
        List<?> locked = entityManager.createNativeQuery(
                "SELECT id FROM diaries WHERE id = :diaryId FOR UPDATE", UUID.class)
                .setParameter("diaryId", diaryIds.getFirst()).getResultList();
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(entityManager.find(Feed.class, feedId, LockModeType.PESSIMISTIC_WRITE));
    }

    public boolean isHiddenForPublicUse(UUID feedId) {
        return entityManager.createQuery("""
                SELECT count(moderation) FROM Feed feed, Feed hiddenFeed, ModerationRestriction moderation
                WHERE feed.id = :feedId AND hiddenFeed.diaryId = feed.diaryId
                  AND moderation.targetType = com.harudle.moderation.domain.ModerationTargetType.FEED
                  AND moderation.targetId = hiddenFeed.id AND moderation.restricted = true
                """, Long.class).setParameter("feedId", feedId).getSingleResult() > 0;
    }

    public Optional<UUID> findActiveAuthorId(UUID diaryId) {
        return entityManager.createQuery(ACTIVE_AUTHOR, UUID.class)
                .setParameter("diaryId", diaryId).getResultList().stream().findFirst();
    }

    public Optional<UUID> findActiveAuthorIdForInteraction(UUID diaryId, UUID actorId) {
        if (!visibility.enabled()) {
            return findActiveAuthorId(diaryId);
        }
        return entityManager.createQuery(ACTIVE_AUTHOR + """
                 AND CASE WHEN author.primaryEmail LIKE '%.loadtest.invalid' THEN TRUE ELSE FALSE END
                   = CASE WHEN EXISTS (SELECT actor.id FROM User actor
                        WHERE actor.id = :actorId AND actor.primaryEmail LIKE '%.loadtest.invalid')
                     THEN TRUE ELSE FALSE END
                """, UUID.class).setParameter("diaryId", diaryId).setParameter("actorId", actorId)
                .getResultList().stream().findFirst();
    }
}
