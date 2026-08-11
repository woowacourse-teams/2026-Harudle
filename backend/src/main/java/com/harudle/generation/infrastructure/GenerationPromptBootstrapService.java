package com.harudle.generation.infrastructure;

import com.harudle.generation.domain.GenerationPrompt;
import com.harudle.generation.repository.GenerationPromptRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GenerationPromptBootstrapService {

    private final GenerationPromptRepository generationPromptRepository;

    GenerationPromptBootstrapService(GenerationPromptRepository generationPromptRepository) {
        this.generationPromptRepository = generationPromptRepository;
    }

    @Transactional
    Optional<GenerationPrompt> createIfEmpty(GenerationPrompt prompt) {
        generationPromptRepository.lockTableForBootstrap();
        if (generationPromptRepository.count() > 0) {
            return Optional.empty();
        }
        return Optional.of(generationPromptRepository.saveAndFlush(prompt));
    }
}
