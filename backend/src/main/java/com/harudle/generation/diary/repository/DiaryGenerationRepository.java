package com.harudle.generation.diary.repository;

import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.domain.GenerationStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DiaryGenerationRepository extends
        JpaRepository<DiaryGeneration, UUID>,
        DiaryGenerationQueryRepository {

    long countByStatusIn(Collection<GenerationStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT generation FROM DiaryGeneration generation WHERE generation.diaryId = :diaryId")
    Optional<DiaryGeneration> findByDiaryIdForUpdate(@Param("diaryId") UUID diaryId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT generation FROM DiaryGeneration generation WHERE generation.id = :id")
    Optional<DiaryGeneration> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT generation
            FROM DiaryGeneration generation
            WHERE generation.idempotencyKey = :idempotencyKey
            """)
    Optional<DiaryGeneration> findByIdempotencyKeyForUpdate(
            @Param("idempotencyKey") UUID idempotencyKey
    );

    @Query(value = """
            SELECT generation.*
            FROM diary_generations AS generation
            WHERE generation.status = 'PENDING'
              AND generation.created_at > :createdAfter
            ORDER BY generation.created_at, generation.id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<DiaryGeneration> findPendingForUpdateSkipLocked(
            @Param("createdAfter") Instant createdAfter,
            @Param("batchSize") int batchSize
    );

    @Query(value = """
            SELECT generation.id
            FROM diary_generations AS generation
            WHERE generation.status = 'PENDING'
              AND generation.created_at <= :expiredBefore
            ORDER BY generation.created_at, generation.id
            LIMIT :batchSize
            """, nativeQuery = true)
    List<UUID> findStalePendingIds(
            @Param("expiredBefore") Instant expiredBefore,
            @Param("batchSize") int batchSize
    );

    @Query("""
            SELECT generation.id
            FROM DiaryGeneration generation
            WHERE generation.status = :processingStatus
              AND generation.updatedAt <= :expiredBefore
            ORDER BY generation.updatedAt, generation.id
            """)
    List<UUID> findStaleProcessingIds(
            @Param("processingStatus") GenerationStatus processingStatus,
            @Param("expiredBefore") Instant expiredBefore,
            Pageable pageable
    );
}
