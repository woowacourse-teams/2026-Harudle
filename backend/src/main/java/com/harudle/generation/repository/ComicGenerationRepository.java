package com.harudle.generation.repository;

import com.harudle.generation.domain.ComicGeneration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComicGenerationRepository extends
        JpaRepository<ComicGeneration, UUID>,
        ComicGenerationQueryRepository {

    Optional<ComicGeneration> findByIdempotencyKey(UUID idempotencyKey);
}
