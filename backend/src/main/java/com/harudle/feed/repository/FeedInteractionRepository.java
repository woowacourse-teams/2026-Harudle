package com.harudle.feed.repository;

import com.harudle.feed.domain.Feed;
import com.harudle.common.testdata.DevTestDataVisibility;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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
        return Optional.ofNullable(entityManager.find(Feed.class, feedId, LockModeType.PESSIMISTIC_WRITE));
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
