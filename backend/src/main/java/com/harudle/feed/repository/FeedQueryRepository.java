package com.harudle.feed.repository;

import com.harudle.feed.domain.Feed;
import com.harudle.common.testdata.DevTestDataVisibility;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

@NoRepositoryBean
public interface FeedQueryRepository extends Repository<Feed, UUID> {

    String ACTIVE_SNAPSHOT = """
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
              AND NOT EXISTS (
                  SELECT block.id FROM UserBlock block
                  WHERE (block.blockerId = :viewerId AND block.blockedId = diary.userId)
                     OR (block.blockerId = diary.userId AND block.blockedId = :viewerId)
              )
            """;

    @Query(ACTIVE_SNAPSHOT)
    Optional<FeedSnapshot> findActiveSnapshotById(@Param("feedId") UUID feedId,
            @Param("viewerId") @Nullable UUID viewerId);

    @Query(ACTIVE_SNAPSHOT + """
             AND EXISTS (SELECT author.id FROM User author WHERE author.id = diary.userId
            """ + DevTestDataVisibility.VISIBLE_AUTHOR_HQL + ")")
    Optional<FeedSnapshot> findVisibleSnapshotById(@Param("feedId") UUID feedId,
            @Param("viewerId") @Nullable UUID viewerId);
}
