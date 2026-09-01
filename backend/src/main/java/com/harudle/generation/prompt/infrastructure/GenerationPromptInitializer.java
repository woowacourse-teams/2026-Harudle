package com.harudle.generation.prompt.infrastructure;

import com.harudle.generation.config.GenerationPromptBootstrapProperties;
import com.harudle.generation.prompt.domain.GenerationPrompt;
import com.harudle.generation.prompt.repository.GenerationPromptRepository;
import com.harudle.generation.diary.service.port.ImageStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

public class GenerationPromptInitializer implements ApplicationRunner, Ordered {

    private static final Logger LOGGER = LoggerFactory.getLogger(GenerationPromptInitializer.class);

    private final GenerationPromptBootstrapProperties properties;
    private final GenerationPromptRepository generationPromptRepository;
    private final ImageStorage imageStorage;
    private final GenerationPromptBootstrapService bootstrapService;

    public GenerationPromptInitializer(
            GenerationPromptBootstrapProperties properties,
            GenerationPromptRepository generationPromptRepository,
            ImageStorage imageStorage,
            GenerationPromptBootstrapService bootstrapService
    ) {
        this.properties = properties;
        this.generationPromptRepository = generationPromptRepository;
        this.imageStorage = imageStorage;
        this.bootstrapService = bootstrapService;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (generationPromptRepository.count() > 0) {
            return;
        }
        GenerationPrompt prompt = properties.createPrompt();
        imageStorage.load(prompt.getImageAssetObjectKey());
        bootstrapService.createIfEmpty(prompt)
                .ifPresent(savedPrompt -> LOGGER.info(
                        "초기 생성 프롬프트를 등록했습니다. promptId={}",
                        savedPrompt.getId()
                ));
    }
}
