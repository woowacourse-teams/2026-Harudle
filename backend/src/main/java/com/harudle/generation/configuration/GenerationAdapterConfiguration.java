package com.harudle.generation.configuration;

import com.google.genai.Client;
import com.google.genai.Models;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.harudle.generation.adapter.out.gemini.DiaryImagePromptRenderer;
import com.harudle.generation.adapter.out.gemini.GeminiDiaryImageGenerator;
import com.harudle.generation.adapter.out.gemini.GeminiExceptionTranslator;
import com.harudle.generation.adapter.out.gemini.GeminiStoryboardGenerator;
import com.harudle.generation.adapter.out.gemini.GeminiStoryboardResponseMapper;
import com.harudle.generation.adapter.out.s3.ImageObjectKeyFactory;
import com.harudle.generation.adapter.out.s3.S3ExceptionTranslator;
import com.harudle.generation.adapter.out.s3.S3ImageStorage;
import com.harudle.generation.repository.DiaryGenerationRepository;
import com.harudle.generation.repository.GenerationPromptRepository;
import com.harudle.generation.service.DiaryGenerationCleanupScheduler;
import com.harudle.generation.service.GenerateDiaryImageService;
import com.harudle.generation.service.RequestFingerprintGenerator;
import com.harudle.generation.service.port.DiaryImageGenerator;
import com.harudle.generation.service.port.ImageStorage;
import com.harudle.generation.service.port.StoryboardGenerator;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class GenerationAdapterConfiguration {

    @Bean
    public Clock generationClock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "close")
    public Client geminiClient(GeminiGenerationProperties properties) {
        int requestTimeoutMillis = Math.toIntExact(properties.requestTimeout().toMillis());
        HttpRetryOptions retryOptions = HttpRetryOptions.builder()
                .attempts(properties.retryAttempts())
                .build();
        HttpOptions httpOptions = HttpOptions.builder()
                .timeout(requestTimeoutMillis)
                .retryOptions(retryOptions)
                .build();

        return Client.builder()
                .apiKey(properties.apiKey())
                .vertexAI(true)
                .httpOptions(httpOptions)
                .build();
    }

    @Bean
    public Models geminiModels(Client geminiClient) {
        return geminiClient.models;
    }

    @Bean(destroyMethod = "close")
    public S3Client s3Client(S3StorageProperties properties) {
        return S3Client.builder()
                .region(Region.of(properties.region()))
                .build();
    }

    @Bean
    public GeminiStoryboardResponseMapper geminiStoryboardResponseMapper() {
        return new GeminiStoryboardResponseMapper();
    }

    @Bean
    public GeminiExceptionTranslator geminiExceptionTranslator() {
        return new GeminiExceptionTranslator();
    }

    @Bean
    public DiaryImagePromptRenderer diaryImagePromptRenderer() {
        return new DiaryImagePromptRenderer();
    }

    @Bean
    public StoryboardGenerator storyboardGenerator(
            Models geminiModels,
            GeminiGenerationProperties properties,
            ObjectMapper objectMapper,
            GeminiStoryboardResponseMapper responseMapper,
            GeminiExceptionTranslator exceptionTranslator
    ) {
        return new GeminiStoryboardGenerator(
                geminiModels,
                properties,
                objectMapper,
                responseMapper,
                exceptionTranslator
        );
    }

    @Bean
    public DiaryImageGenerator diaryImageGenerator(
            Models geminiModels,
            GeminiGenerationProperties properties,
            DiaryImagePromptRenderer promptRenderer,
            GeminiExceptionTranslator exceptionTranslator
    ) {
        return new GeminiDiaryImageGenerator(
                geminiModels,
                properties,
                promptRenderer,
                exceptionTranslator
        );
    }

    @Bean
    public ImageObjectKeyFactory imageObjectKeyFactory(S3StorageProperties properties) {
        return new ImageObjectKeyFactory(properties);
    }

    @Bean
    public S3ExceptionTranslator s3ExceptionTranslator() {
        return new S3ExceptionTranslator();
    }

    @Bean
    public ImageStorage imageStorage(
            S3Client s3Client,
            S3StorageProperties properties,
            ImageObjectKeyFactory objectKeyFactory,
            S3ExceptionTranslator exceptionTranslator
    ) {
        return new S3ImageStorage(
                s3Client,
                properties,
                objectKeyFactory,
                exceptionTranslator
        );
    }

    @Bean
    public GenerateDiaryImageService generateDiaryImageService(
            RequestFingerprintGenerator requestFingerprintGenerator,
            GenerationLifecycleProperties generationLifecycleProperties,
            Clock generationClock,
            GenerationPromptRepository generationPromptRepository,
            DiaryGenerationRepository diaryGenerationRepository,
            StoryboardGenerator storyboardGenerator,
            DiaryImageGenerator diaryImageGenerator,
            ImageStorage imageStorage
    ) {
        return new GenerateDiaryImageService(
                requestFingerprintGenerator,
                generationLifecycleProperties,
                generationClock,
                generationPromptRepository,
                diaryGenerationRepository,
                storyboardGenerator,
                diaryImageGenerator,
                imageStorage
        );
    }

    @Bean
    public DiaryGenerationCleanupScheduler diaryGenerationCleanupScheduler(
            DiaryGenerationRepository diaryGenerationRepository,
            GenerationLifecycleProperties generationLifecycleProperties,
            Clock generationClock
    ) {
        return new DiaryGenerationCleanupScheduler(
                diaryGenerationRepository,
                generationLifecycleProperties,
                generationClock
        );
    }
}
