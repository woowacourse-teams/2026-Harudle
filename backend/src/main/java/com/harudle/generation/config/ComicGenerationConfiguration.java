package com.harudle.generation.config;

import com.harudle.generation.infrastructure.GenerationPromptBootstrapService;
import com.harudle.generation.infrastructure.GenerationPromptInitializer;
import com.harudle.generation.repository.ComicGenerationRepository;
import com.harudle.generation.repository.GenerationPromptRepository;
import com.harudle.generation.service.ClaimedComicGenerationService;
import com.harudle.generation.service.ComicGenerationCompletionService;
import com.harudle.generation.service.RequestFingerprintGenerator;
import com.harudle.generation.service.port.ComicImageGenerator;
import com.harudle.generation.service.port.ImageStorage;
import com.harudle.generation.service.port.StoryboardGenerator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GenerationPromptBootstrapProperties.class)
public class ComicGenerationConfiguration {

    @Bean
    ClaimedComicGenerationService claimedComicGenerationService(
            RequestFingerprintGenerator requestFingerprintGenerator,
            GenerationPromptRepository generationPromptRepository,
            ComicGenerationRepository comicGenerationRepository,
            ObjectProvider<StoryboardGenerator> storyboardGeneratorProvider,
            ObjectProvider<ComicImageGenerator> comicImageGeneratorProvider,
            ObjectProvider<ImageStorage> imageStorageProvider,
            ComicGenerationCompletionService completionService
    ) {
        return new ClaimedComicGenerationService(
                requestFingerprintGenerator,
                generationPromptRepository,
                comicGenerationRepository,
                storyboardGeneratorProvider,
                comicImageGeneratorProvider,
                imageStorageProvider,
                completionService
        );
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "harudle.generation.prompt-bootstrap",
            name = "enabled",
            havingValue = "true"
    )
    GenerationPromptInitializer generationPromptInitializer(
            GenerationPromptBootstrapProperties properties,
            GenerationPromptRepository generationPromptRepository,
            ImageStorage imageStorage,
            GenerationPromptBootstrapService bootstrapService
    ) {
        return new GenerationPromptInitializer(
                properties,
                generationPromptRepository,
                imageStorage,
                bootstrapService
        );
    }
}
