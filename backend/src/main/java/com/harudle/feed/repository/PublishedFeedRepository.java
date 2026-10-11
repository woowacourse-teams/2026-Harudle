package com.harudle.feed.repository;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class PublishedFeedRepository {
    private final EntityManager entityManager;

    public PublishedFeedRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public List<PublishedFeedSnapshot> findActiveSnapshotsByDiaryIds(Set<UUID> diaryIds) {
        return entityManager.createQuery("""
                SELECT new com.harudle.feed.repository.PublishedFeedSnapshot(diary.id, feed.id)
                FROM Feed feed, Diary diary
                WHERE diary.id = feed.diaryId
                  AND diary.id IN :diaryIds
                  AND diary.deletedAt IS NULL
                  AND feed.deletedAt IS NULL
                """, PublishedFeedSnapshot.class).setParameter("diaryIds", diaryIds).getResultList();
    }
}
