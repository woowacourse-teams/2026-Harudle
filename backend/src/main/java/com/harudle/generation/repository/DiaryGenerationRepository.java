package com.harudle.generation.repository;

import com.harudle.generation.domain.DiaryGeneration;
import com.harudle.generation.domain.GenerationErrorCode;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.domain.Storyboard;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DiaryGenerationRepository extends JpaRepository<DiaryGeneration, UUID> {

    Optional<DiaryGeneration> findByIdempotencyKey(UUID idempotencyKey);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE DiaryGeneration generation
            SET generation.status = :succeededStatus,
                generation.storyboard = :storyboard,
                generation.title = :title,
                generation.imageObjectKey = :imageObjectKey,
                generation.errorCode = null,
                generation.completedAt = :completedAt,
                generation.updatedAt = :completedAt
            WHERE generation.id = :generationId
              AND generation.status = :processingStatus
            """)
    int succeedProcessingGeneration(
            @Param("generationId") UUID generationId,
            @Param("storyboard") Storyboard storyboard,
            @Param("title") String title,
            @Param("imageObjectKey") String imageObjectKey,
            @Param("completedAt") Instant completedAt,
            @Param("processingStatus") GenerationStatus processingStatus,
            @Param("succeededStatus") GenerationStatus succeededStatus
    );

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE DiaryGeneration generation
            SET generation.status = :failedStatus,
                generation.errorCode = :errorCode,
                generation.completedAt = :completedAt,
                generation.updatedAt = :completedAt
            WHERE generation.id = :generationId
              AND generation.status = :processingStatus
            """)
    int failProcessingGeneration(
            @Param("generationId") UUID generationId,
            @Param("errorCode") GenerationErrorCode errorCode,
            @Param("completedAt") Instant completedAt,
            @Param("processingStatus") GenerationStatus processingStatus,
            @Param("failedStatus") GenerationStatus failedStatus
    );

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE comic_generations
            SET status = 'FAILED',
                error_code = 'GENERATION_INTERRUPTED',
                completed_at = :completedAt,
                updated_at = :completedAt
            WHERE id = :generationId
              AND status = 'PROCESSING'
              AND updated_at < :expiredBefore
            """, nativeQuery = true)
    int expireProcessingGeneration(
            @Param("generationId") UUID generationId,
            @Param("expiredBefore") Instant expiredBefore,
            @Param("completedAt") Instant completedAt
    );

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE comic_generations
            SET status = 'FAILED',
                error_code = 'GENERATION_INTERRUPTED',
                completed_at = :completedAt,
                updated_at = :completedAt
            WHERE status = 'PROCESSING'
              AND updated_at < :expiredBefore
            """, nativeQuery = true)
    int expireProcessingGenerations(
            @Param("expiredBefore") Instant expiredBefore,
            @Param("completedAt") Instant completedAt
    );
}
