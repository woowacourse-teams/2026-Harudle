package com.harudle.generation.config;

import com.harudle.generation.prompt.infrastructure.GenerationPromptBootstrapService;
import com.harudle.generation.prompt.infrastructure.GenerationPromptInitializer;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.prompt.repository.GenerationPromptRepository;
import com.harudle.generation.diary.service.ClaimedDiaryGenerationService;
import com.harudle.generation.diary.service.DiaryGenerationCompletionService;
import com.harudle.generation.diary.service.DiaryGenerationExecutor;
import com.harudle.generation.diary.service.RequestFingerprintGenerator;
import com.harudle.generation.diary.service.dto.CompletedDiaryGeneration;
import com.harudle.generation.diary.service.dto.GenerateDiaryImageCommand;
import com.harudle.generation.diary.service.exception.GenerationUnavailableException;
import com.harudle.generation.diary.service.port.DiaryImageGenerator;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.StoryboardGenerator;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({
        GenerationPromptBootstrapProperties.class,
        GenerationLifecycleProperties.class
})
class DiaryGenerationConfiguration {

    @Bean
    DiaryGenerationExecutor diaryGenerationExecutor(
            RequestFingerprintGenerator requestFingerprintGenerator,
            GenerationPromptRepository generationPromptRepository,
            DiaryGenerationRepository diaryGenerationRepository,
            ObjectProvider<StoryboardGenerator> storyboardGeneratorProvider,
            ObjectProvider<DiaryImageGenerator> diaryImageGeneratorProvider,
            ObjectProvider<ImageStorage> imageStorageProvider,
            DiaryGenerationCompletionService completionService
    ) {
        Optional<StoryboardGenerator> storyboardGenerator = findAdapter(storyboardGeneratorProvider);
        Optional<DiaryImageGenerator> diaryImageGenerator = findAdapter(diaryImageGeneratorProvider);
        Optional<ImageStorage> imageStorage = findAdapter(imageStorageProvider);
        if (storyboardGenerator.isEmpty()
                && diaryImageGenerator.isEmpty()
                && imageStorage.isEmpty()) {
            return new UnavailableDiaryGenerationExecutor();
        }
        if (storyboardGenerator.isEmpty()
                || diaryImageGenerator.isEmpty()
                || imageStorage.isEmpty()) {
            throw new IllegalStateException("AI 생성 어댑터는 모두 함께 구성해야 합니다.");
        }

        return new ClaimedDiaryGenerationService(
                requestFingerprintGenerator,
                generationPromptRepository,
                diaryGenerationRepository,
                storyboardGenerator.orElseThrow(),
                diaryImageGenerator.orElseThrow(),
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

    private static final class UnavailableDiaryGenerationExecutor implements DiaryGenerationExecutor {

        @Override
        public boolean isConfigured() {
            return false;
        }

        @Override
        public CompletedDiaryGeneration generate(GenerateDiaryImageCommand command, UUID generationId) {
            throw GenerationUnavailableException.adaptersNotConfigured();
        }
    }
}
