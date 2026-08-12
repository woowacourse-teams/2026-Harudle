package com.harudle.generation.repository;

import com.harudle.generation.domain.GenerationPrompt;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenerationPromptRepository extends JpaRepository<GenerationPrompt, Long> {

    Optional<GenerationPrompt> findFirstByOrderByIdDesc();
}
