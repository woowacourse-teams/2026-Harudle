package com.harudle.generation.configuration;

import com.google.genai.Client;
import com.google.genai.Models;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.harudle.generation.adapter.out.gemini.ComicImagePromptRenderer;
import com.harudle.generation.adapter.out.gemini.GeminiComicImageGenerator;
import com.harudle.generation.adapter.out.gemini.GeminiExceptionTranslator;
import com.harudle.generation.adapter.out.gemini.GeminiStoryboardGenerator;
import com.harudle.generation.adapter.out.gemini.GeminiStoryboardResponseMapper;
import com.harudle.generation.adapter.out.s3.ImageObjectKeyFactory;
import com.harudle.generation.adapter.out.s3.S3ExceptionTranslator;
import com.harudle.generation.adapter.out.s3.S3ImageStorage;
import com.harudle.generation.repository.ComicGenerationRepository;
import com.harudle.generation.repository.GenerationPromptRepository;
import com.harudle.generation.service.GenerateComicService;
import com.harudle.generation.service.RequestFingerprintGenerator;
import com.harudle.generation.service.port.ComicImageGenerator;
import com.harudle.generation.service.port.ImageStorage;
import com.harudle.generation.service.port.StoryboardGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class GenerationAdapterConfiguration {

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
    public ComicImagePromptRenderer comicImagePromptRenderer() {
        return new ComicImagePromptRenderer();
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
    public ComicImageGenerator comicImageGenerator(
            Models geminiModels,
            GeminiGenerationProperties properties,
            ComicImagePromptRenderer promptRenderer,
            GeminiExceptionTranslator exceptionTranslator
    ) {
        return new GeminiComicImageGenerator(
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
    public GenerateComicService generateComicService(
            RequestFingerprintGenerator requestFingerprintGenerator,
            GenerationPromptRepository generationPromptRepository,
            ComicGenerationRepository comicGenerationRepository,
            StoryboardGenerator storyboardGenerator,
            ComicImageGenerator comicImageGenerator,
            ImageStorage imageStorage
    ) {
        return new GenerateComicService(
                requestFingerprintGenerator,
                generationPromptRepository,
                comicGenerationRepository,
                storyboardGenerator,
                comicImageGenerator,
                imageStorage
        );
    }
}
