package com.harudle.generation.repository;

import com.harudle.generation.domain.DiaryGeneration;
import com.harudle.generation.domain.GenerationStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
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
