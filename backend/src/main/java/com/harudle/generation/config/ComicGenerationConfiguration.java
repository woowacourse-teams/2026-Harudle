package com.harudle.generation.config;

import com.harudle.generation.infrastructure.GenerationPromptBootstrapService;
import com.harudle.generation.infrastructure.GenerationPromptInitializer;
import com.harudle.generation.repository.ComicGenerationRepository;
import com.harudle.generation.repository.GenerationPromptRepository;
import com.harudle.generation.service.ClaimedComicGenerationService;
import com.harudle.generation.service.ComicGenerationCompletionService;
import com.harudle.generation.service.ComicGenerationExecutor;
import com.harudle.generation.service.RequestFingerprintGenerator;
import com.harudle.generation.service.dto.CompletedComicGeneration;
import com.harudle.generation.service.dto.GenerateComicCommand;
import com.harudle.generation.service.exception.GenerationUnavailableException;
import com.harudle.generation.service.port.ComicImageGenerator;
import com.harudle.generation.service.port.ImageStorage;
import com.harudle.generation.service.port.StoryboardGenerator;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GenerationPromptBootstrapProperties.class)
class ComicGenerationConfiguration {

    @Bean
    ComicGenerationExecutor comicGenerationExecutor(
            RequestFingerprintGenerator requestFingerprintGenerator,
            GenerationPromptRepository generationPromptRepository,
            ComicGenerationRepository comicGenerationRepository,
            ObjectProvider<StoryboardGenerator> storyboardGeneratorProvider,
            ObjectProvider<ComicImageGenerator> comicImageGeneratorProvider,
            ObjectProvider<ImageStorage> imageStorageProvider,
            ComicGenerationCompletionService completionService
    ) {
        Optional<StoryboardGenerator> storyboardGenerator = findAdapter(storyboardGeneratorProvider);
        Optional<ComicImageGenerator> comicImageGenerator = findAdapter(comicImageGeneratorProvider);
        Optional<ImageStorage> imageStorage = findAdapter(imageStorageProvider);
        if (storyboardGenerator.isEmpty()
                && comicImageGenerator.isEmpty()
                && imageStorage.isEmpty()) {
            return new UnavailableComicGenerationExecutor();
        }
        if (storyboardGenerator.isEmpty()
                || comicImageGenerator.isEmpty()
                || imageStorage.isEmpty()) {
            throw new IllegalStateException("AI 생성 어댑터는 모두 함께 구성해야 합니다.");
        }
        return new ClaimedComicGenerationService(
                requestFingerprintGenerator,
                generationPromptRepository,
                comicGenerationRepository,
                storyboardGenerator.orElseThrow(),
                comicImageGenerator.orElseThrow(),
                imageStorage.orElseThrow(),
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

    private static <T> Optional<T> findAdapter(ObjectProvider<T> provider) {
        return Optional.ofNullable(provider.getIfAvailable());
    }

    private static final class UnavailableComicGenerationExecutor implements ComicGenerationExecutor {

        @Override
        public boolean isConfigured() {
            return false;
        }

        @Override
        public CompletedComicGeneration generate(GenerateComicCommand command, UUID generationId) {
            throw GenerationUnavailableException.adaptersNotConfigured();
        }
    }
}
