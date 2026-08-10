package com.harudle.generation.infrastructure;

import com.harudle.generation.domain.GenerationPrompt;
import com.harudle.generation.repository.GenerationPromptRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GenerationPromptBootstrapService {

    private static final String LOCK_PROMPT_TABLE_SQL =
            "LOCK TABLE generation_prompts IN SHARE ROW EXCLUSIVE MODE";

    private final GenerationPromptRepository generationPromptRepository;
    private final JdbcTemplate jdbcTemplate;

    public GenerationPromptBootstrapService(
            GenerationPromptRepository generationPromptRepository,
            JdbcTemplate jdbcTemplate
    ) {
        this.generationPromptRepository = generationPromptRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public GenerationPrompt createIfEmpty(GenerationPrompt prompt) {
        jdbcTemplate.execute(LOCK_PROMPT_TABLE_SQL);
        if (generationPromptRepository.count() > 0) {
            return null;
        }
        return generationPromptRepository.saveAndFlush(prompt);
    }
}
