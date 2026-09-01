package com.harudle.admin.repository;

import com.harudle.admin.query.AdminGenerationHistorySnapshot;
import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.domain.GenerationStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AdminGenerationHistoryQueryRepository extends Repository<DiaryGeneration, UUID> {

    @Transactional(readOnly = true)
    @Query(
            value = """
                    SELECT new com.harudle.admin.query.AdminGenerationHistorySnapshot(
                        generation.id,
                        user.id,
                        user.name,
                        generation.createdAt,
                        generation.status,
                        generation.completedAt,
                        generation.errorCode
                    )
                    FROM DiaryGeneration generation
                    JOIN Diary diary ON diary.id = generation.diaryId
                    JOIN User user ON user.id = diary.userId
                    WHERE (:userId IS NULL OR diary.userId = :userId)
                      AND (:status IS NULL OR generation.status = :status)
                      AND (CAST(:fromInclusive AS java.time.Instant) IS NULL
                           OR generation.createdAt >= :fromInclusive)
                      AND (CAST(:toExclusive AS java.time.Instant) IS NULL
                           OR generation.createdAt < :toExclusive)
                    ORDER BY generation.createdAt DESC, generation.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(generation.id)
                    FROM DiaryGeneration generation
                    JOIN Diary diary ON diary.id = generation.diaryId
                    JOIN User user ON user.id = diary.userId
                    WHERE (:userId IS NULL OR diary.userId = :userId)
                      AND (:status IS NULL OR generation.status = :status)
                      AND (CAST(:fromInclusive AS java.time.Instant) IS NULL
                           OR generation.createdAt >= :fromInclusive)
                      AND (CAST(:toExclusive AS java.time.Instant) IS NULL
                           OR generation.createdAt < :toExclusive)
                    """
    )
    Page<AdminGenerationHistorySnapshot> search(
            @Param("userId") UUID userId,
            @Param("status") GenerationStatus status,
            @Param("fromInclusive") Instant fromInclusive,
            @Param("toExclusive") Instant toExclusive,
            Pageable pageable
    );
}
