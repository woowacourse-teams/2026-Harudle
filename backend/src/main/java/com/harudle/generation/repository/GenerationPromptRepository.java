package com.harudle.generation.repository;

import com.harudle.generation.domain.GenerationPrompt;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface GenerationPromptRepository extends JpaRepository<GenerationPrompt, Long> {

    Optional<GenerationPrompt> findFirstByOrderByIdDesc();

    @Modifying
    @Query(value = "LOCK TABLE generation_prompts IN SHARE ROW EXCLUSIVE MODE", nativeQuery = true)
    void lockTableForBootstrap();
}
