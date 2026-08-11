package com.harudle.generation.repository;

import com.harudle.generation.domain.DiaryGeneration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiaryGenerationRepository extends JpaRepository<DiaryGeneration, UUID> {

    Optional<DiaryGeneration> findByIdempotencyKey(UUID idempotencyKey);
}
