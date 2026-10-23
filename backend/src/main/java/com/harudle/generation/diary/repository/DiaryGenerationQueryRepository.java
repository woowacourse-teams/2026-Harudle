package com.harudle.generation.diary.repository;

import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.domain.GenerationStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

@NoRepositoryBean
public interface DiaryGenerationQueryRepository extends Repository<DiaryGeneration, UUID> {

    @Query("""
            SELECT new com.harudle.generation.diary.repository.ImageBackupTarget(
                generation.id, generation.completedAt, generation.imageObjectKey
            )
            FROM DiaryGeneration generation
            WHERE generation.status = :status
              AND generation.completedAt <= :completedBefore
              AND generation.imageObjectKey LIKE :keyPattern
            ORDER BY generation.completedAt, generation.id
            """)
    List<ImageBackupTarget> findImageBackupTargets(
            @Param("status") GenerationStatus status,
            @Param("completedBefore") Instant completedBefore,
            @Param("keyPattern") String keyPattern,
            Pageable pageable
    );

    @Query("""
            SELECT new com.harudle.generation.diary.repository.ImageBackupTarget(
                generation.id, generation.completedAt, generation.imageObjectKey
            )
            FROM DiaryGeneration generation
            WHERE generation.status = :status
              AND generation.completedAt <= :completedBefore
              AND generation.imageObjectKey LIKE :keyPattern
              AND (generation.completedAt > :afterCompletedAt
                   OR (generation.completedAt = :afterCompletedAt AND generation.id > :afterId))
            ORDER BY generation.completedAt, generation.id
            """)
    List<ImageBackupTarget> findImageBackupTargetsAfter(
            @Param("status") GenerationStatus status,
            @Param("completedBefore") Instant completedBefore,
            @Param("keyPattern") String keyPattern,
            @Param("afterCompletedAt") Instant afterCompletedAt,
            @Param("afterId") UUID afterId,
            Pageable pageable
    );

    @Query("""
            SELECT new com.harudle.generation.diary.repository.DiaryGenerationSnapshot(
                generation.id,
                generation.diaryId,
                generation.status,
                generation.title,
                generation.imageObjectKey,
                generation.completedAt,
                generation.tokenUsage
            )
            FROM DiaryGeneration generation
            WHERE generation.diaryId = :diaryId
            """)
    Optional<DiaryGenerationSnapshot> findSnapshotByDiaryId(@Param("diaryId") UUID diaryId);

    @Query("""
            SELECT new com.harudle.generation.diary.repository.DiaryGenerationSnapshot(
                generation.id,
                generation.diaryId,
                generation.status,
                generation.title,
                generation.imageObjectKey,
                generation.completedAt,
                generation.tokenUsage
            )
            FROM DiaryGeneration generation
            WHERE generation.diaryId IN :diaryIds
              AND generation.status = :status
            """)
    List<DiaryGenerationSnapshot> findSnapshotsByDiaryIdInAndStatus(
            @Param("diaryIds") Collection<UUID> diaryIds,
            @Param("status") GenerationStatus status
    );
}
