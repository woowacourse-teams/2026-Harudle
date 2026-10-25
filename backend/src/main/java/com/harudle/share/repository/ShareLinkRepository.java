package com.harudle.share.repository;

import com.harudle.share.domain.ShareLink;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShareLinkRepository extends
        JpaRepository<ShareLink, UUID>,
        ShareLinkDeletionRepository {

    Optional<ShareLink> findByGenerationId(UUID generationId);

    @Query("""
            SELECT new com.harudle.share.repository.PublicShareSnapshot(
                generation.title,
                diary.diaryDate,
                generation.imageObjectKey,
                diary.createdAt
            )
            FROM ShareLink shareLink, DiaryGeneration generation, Diary diary
            WHERE shareLink.id = :shareId
              AND generation.id = shareLink.generationId
              AND diary.id = generation.diaryId
              AND diary.deletedAt IS NULL
              AND generation.status = com.harudle.generation.diary.domain.GenerationStatus.SUCCEEDED
              AND NOT EXISTS (
                  SELECT block.id FROM UserBlock block
                  WHERE (block.blockerId = :viewerId AND block.blockedId = diary.userId)
                     OR (block.blockerId = diary.userId AND block.blockedId = :viewerId)
              )
              AND NOT EXISTS (SELECT hiddenFeed.id FROM Feed hiddenFeed, ModerationRestriction moderation
                  WHERE hiddenFeed.diaryId = diary.id
                    AND moderation.targetType = com.harudle.moderation.domain.ModerationTargetType.FEED
                    AND moderation.targetId = hiddenFeed.id AND moderation.restricted = true)
            """)
    Optional<PublicShareSnapshot> findPublicSnapshotById(@Param("shareId") UUID shareId,
            @Param("viewerId") @Nullable UUID viewerId);
}
