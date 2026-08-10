package com.harudle.share.repository;

import com.harudle.share.domain.ShareLink;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShareLinkRepository extends JpaRepository<ShareLink, UUID> {

    @Modifying(flushAutomatically = true)
    @Query("""
            DELETE FROM ShareLink shareLink
            WHERE shareLink.generationId IN (
                SELECT generation.id
                FROM ComicGeneration generation
                WHERE generation.diaryId = :diaryId
            )
            """)
    int deleteByDiaryId(@Param("diaryId") UUID diaryId);
}
