package com.harudle.generation.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.harudle.generation.domain.ComicGeneration;

public interface ComicGenerationRepository extends JpaRepository<ComicGeneration, UUID> {

    Optional<ComicGeneration> findByIdempotencyKey(UUID idempotencyKey);
}
