package com.harudle.feed.repository;

import com.harudle.feed.domain.Feed;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

@NoRepositoryBean
public interface FeedQueryRepository extends Repository<Feed, UUID> {

    @Query("""
            SELECT new com.harudle.feed.repository.FeedSnapshot(
                feed.id, diary.userId, feed.categoryId, generation.imageObjectKey,
                feed.publishedAt, feed.likeCount, feed.commentCount
            )
            FROM Feed feed, Diary diary, DiaryGeneration generation
            WHERE feed.id = :feedId
              AND diary.id = feed.diaryId
              AND generation.diaryId = diary.id
              AND feed.deletedAt IS NULL
              AND diary.deletedAt IS NULL
              AND generation.status = com.harudle.generation.diary.domain.GenerationStatus.SUCCEEDED
            """)
    Optional<FeedSnapshot> findActiveSnapshotById(@Param("feedId") UUID feedId);
}
