package com.harudle.share.repository;

import com.harudle.share.domain.ShareLink;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

@NoRepositoryBean
public interface ShareLinkDeletionRepository extends Repository<ShareLink, UUID> {

    @Modifying(flushAutomatically = true)
    @Query("""
            DELETE FROM ShareLink shareLink
            WHERE shareLink.generationId IN (
                SELECT generation.id
                FROM DiaryGeneration generation
                WHERE generation.diaryId = :diaryId
            )
            """)
    int deleteAllByDiaryId(@Param("diaryId") UUID diaryId);
}
