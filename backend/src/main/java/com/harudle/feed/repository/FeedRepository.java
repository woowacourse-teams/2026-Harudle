package com.harudle.feed.repository;

import com.harudle.feed.domain.Feed;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeedRepository extends JpaRepository<Feed, UUID>, FeedQueryRepository {

    boolean existsByDiaryIdAndDeletedAtIsNull(UUID diaryId);

    @Query("""
            SELECT count(moderation) > 0 FROM Feed hiddenFeed, ModerationRestriction moderation
            WHERE hiddenFeed.diaryId = :diaryId
              AND moderation.targetType = com.harudle.moderation.domain.ModerationTargetType.FEED
              AND moderation.targetId = hiddenFeed.id AND moderation.restricted = true
            """)
    boolean isRestrictedForPublication(@Param("diaryId") UUID diaryId);
}
