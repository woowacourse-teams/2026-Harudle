package com.harudle.generation.repository;

import com.harudle.generation.domain.ComicGeneration;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ComicGenerationRepository extends JpaRepository<ComicGeneration, UUID> {

    Optional<ComicGeneration> findByIdempotencyKey(UUID idempotencyKey);

    Optional<ComicGeneration> findByDiaryId(UUID diaryId);

    List<ComicGeneration> findAllByDiaryIdIn(Collection<UUID> diaryIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT generation FROM ComicGeneration generation WHERE generation.id = :id")
    Optional<ComicGeneration> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT generation
            FROM ComicGeneration generation
            WHERE generation.idempotencyKey = :idempotencyKey
            """)
    Optional<ComicGeneration> findByIdempotencyKeyForUpdate(
            @Param("idempotencyKey") UUID idempotencyKey
    );
}
