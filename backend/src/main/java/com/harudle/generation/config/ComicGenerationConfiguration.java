package com.harudle.generation.config;

import com.harudle.generation.repository.ComicGenerationRepository;
import com.harudle.generation.repository.GenerationPromptRepository;
import com.harudle.generation.service.ClaimedComicGenerationService;
import com.harudle.generation.service.ComicGenerationCompletionService;
import com.harudle.generation.service.RequestFingerprintGenerator;
import com.harudle.generation.service.port.ComicImageGenerator;
import com.harudle.generation.service.port.ImageStorage;
import com.harudle.generation.service.port.StoryboardGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ComicGenerationConfiguration {

    @Bean
    @ConditionalOnBean({StoryboardGenerator.class, ComicImageGenerator.class, ImageStorage.class})
    ClaimedComicGenerationService claimedComicGenerationService(
            RequestFingerprintGenerator requestFingerprintGenerator,
            GenerationPromptRepository generationPromptRepository,
            ComicGenerationRepository comicGenerationRepository,
            StoryboardGenerator storyboardGenerator,
            ComicImageGenerator comicImageGenerator,
            ImageStorage imageStorage,
            ComicGenerationCompletionService completionService
    ) {
        return new ClaimedComicGenerationService(
                requestFingerprintGenerator,
                generationPromptRepository,
                comicGenerationRepository,
                storyboardGenerator,
                comicImageGenerator,
                imageStorage,
                completionService
        );
    }
}
