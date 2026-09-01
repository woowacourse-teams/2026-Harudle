package com.harudle.generation.diary.repository;

import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.domain.GenerationStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

@NoRepositoryBean
public interface DiaryGenerationQueryRepository extends Repository<DiaryGeneration, UUID> {

    @Query("""
            SELECT new com.harudle.generation.diary.repository.DiaryGenerationSnapshot(
                generation.id,
                generation.diaryId,
                generation.status,
                generation.title,
                generation.imageObjectKey,
                generation.completedAt
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
                generation.completedAt
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
