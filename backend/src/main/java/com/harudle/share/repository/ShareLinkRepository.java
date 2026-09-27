package com.harudle.share.repository;

import com.harudle.share.domain.ShareLink;
import java.util.Optional;
import java.util.UUID;
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
            """)
    Optional<PublicShareSnapshot> findPublicSnapshotById(@Param("shareId") UUID shareId);
}
