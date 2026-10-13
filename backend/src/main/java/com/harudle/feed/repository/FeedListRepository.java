package com.harudle.feed.repository;

import com.harudle.feed.query.FeedCursor;
import com.harudle.feed.query.FeedSort;
import com.harudle.generation.diary.domain.GenerationStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

@Repository
public class FeedListRepository {
    private static final String SELECT_ACTIVE = """
            SELECT new com.harudle.feed.repository.FeedSnapshot(
                feed.id, diary.userId, feed.categoryId, generation.imageObjectKey,
                feed.publishedAt, feed.likeCount, feed.commentCount
            )
            FROM Feed feed, Diary diary, DiaryGeneration generation, User author
            WHERE diary.id = feed.diaryId
              AND generation.diaryId = diary.id
              AND author.id = diary.userId
              AND feed.deletedAt IS NULL
              AND diary.deletedAt IS NULL
              AND author.deletedAt IS NULL
              AND generation.status = :succeeded
            """;
    private static final String AFTER_LATEST = """
            (feed.publishedAt < :cursorPublishedAt
                OR (feed.publishedAt = :cursorPublishedAt AND feed.id < :cursorFeedId))
            """;

    private final EntityManager entityManager;

    public FeedListRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public List<FeedSnapshot> findAfter(
            FeedSort sort, @Nullable Long categoryId, @Nullable FeedCursor cursor, int limit
    ) {
        var hql = new StringBuilder(SELECT_ACTIVE);
        if (categoryId != null) {
            hql.append(" AND feed.categoryId = :categoryId");
        }
        if (cursor != null) {
            hql.append(" AND ");
            if (sort == FeedSort.POPULAR) {
                hql.append("(feed.likeCount < :cursorLikeCount OR (feed.likeCount = :cursorLikeCount AND ")
                        .append(AFTER_LATEST).append("))");
            } else {
                hql.append(AFTER_LATEST);
            }
        }
        hql.append(sort == FeedSort.POPULAR
                ? " ORDER BY feed.likeCount DESC, feed.publishedAt DESC, feed.id DESC"
                : " ORDER BY feed.publishedAt DESC, feed.id DESC");
        TypedQuery<FeedSnapshot> query = entityManager.createQuery(hql.toString(), FeedSnapshot.class)
                .setParameter("succeeded", GenerationStatus.SUCCEEDED).setMaxResults(limit);
        if (categoryId != null) {
            query.setParameter("categoryId", categoryId);
        }
        if (cursor != null) {
            query.setParameter("cursorPublishedAt", cursor.publishedAt());
            query.setParameter("cursorFeedId", cursor.feedId());
            if (sort == FeedSort.POPULAR) {
                query.setParameter("cursorLikeCount", cursor.likeCount());
            }
        }
        return query.getResultList();
    }
}
