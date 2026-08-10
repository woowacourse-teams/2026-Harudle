package com.harudle.generation.infrastructure;

import com.harudle.generation.domain.GenerationPrompt;
import com.harudle.generation.repository.GenerationPromptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GenerationPromptBootstrapService {

    private final GenerationPromptRepository generationPromptRepository;

    public GenerationPromptBootstrapService(GenerationPromptRepository generationPromptRepository) {
        this.generationPromptRepository = generationPromptRepository;
    }

    @Transactional
    public GenerationPrompt createIfEmpty(GenerationPrompt prompt) {
        generationPromptRepository.lockTableForBootstrap();
        if (generationPromptRepository.count() > 0) {
            return null;
        }
        return generationPromptRepository.saveAndFlush(prompt);
    }
}
