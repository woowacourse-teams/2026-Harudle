package com.harudle.generation.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.harudle.generation.domain.GenerationPrompt;

public interface GenerationPromptRepository extends JpaRepository<GenerationPrompt, Long> {

    Optional<GenerationPrompt> findFirstByOrderByIdDesc();
}
