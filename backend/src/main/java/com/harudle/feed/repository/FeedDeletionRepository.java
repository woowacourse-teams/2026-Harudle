package com.harudle.feed.repository;

import com.harudle.feed.domain.Feed;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class FeedDeletionRepository {
    private final EntityManager entityManager;

    public FeedDeletionRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Optional<Feed> findActiveByDiaryIdForUpdate(UUID diaryId) {
        return entityManager.createQuery("""
                SELECT feed FROM Feed feed
                WHERE feed.diaryId = :diaryId AND feed.deletedAt IS NULL
                """, Feed.class).setParameter("diaryId", diaryId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList().stream().findFirst();
    }
}
