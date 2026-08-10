package com.harudle.generation.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.harudle.generation.domain.GenerationPrompt;

public interface GenerationPromptRepository extends JpaRepository<GenerationPrompt, Long> {
}
