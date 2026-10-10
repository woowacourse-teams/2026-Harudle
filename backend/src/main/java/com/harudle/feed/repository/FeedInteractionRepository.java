package com.harudle.feed.repository;

import com.harudle.feed.domain.Feed;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class FeedInteractionRepository {
    private final EntityManager entityManager;

    public FeedInteractionRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Optional<Feed> findByIdForUpdate(UUID feedId) {
        return Optional.ofNullable(entityManager.find(Feed.class, feedId, LockModeType.PESSIMISTIC_WRITE));
    }

    public Optional<UUID> findActiveAuthorId(UUID diaryId) {
        return entityManager.createQuery("""
                SELECT diary.userId FROM Diary diary, User author
                WHERE diary.id = :diaryId AND author.id = diary.userId
                  AND diary.deletedAt IS NULL AND author.deletedAt IS NULL
                """, UUID.class).setParameter("diaryId", diaryId).getResultList().stream().findFirst();
    }
}
