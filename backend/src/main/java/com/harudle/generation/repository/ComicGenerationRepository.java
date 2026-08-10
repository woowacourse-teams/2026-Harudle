package com.harudle.generation.repository;

import com.harudle.generation.domain.ComicGeneration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComicGenerationRepository extends JpaRepository<ComicGeneration, UUID> {

    Optional<ComicGeneration> findByIdempotencyKey(UUID idempotencyKey);

    Optional<ComicGeneration> findByDiaryId(UUID diaryId);

    List<ComicGeneration> findAllByDiaryIdIn(Collection<UUID> diaryIds);
}
