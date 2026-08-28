package com.harudle.generation.configuration;

import com.google.genai.Client;
import com.google.genai.Models;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.adapter.out.gemini.DiaryImagePromptRenderer;
import com.harudle.generation.adapter.out.gemini.GeminiDiaryImageGenerator;
import com.harudle.generation.adapter.out.gemini.GeminiExceptionTranslator;
import com.harudle.generation.adapter.out.gemini.GeminiFailureReporter;
import com.harudle.generation.adapter.out.gemini.GeminiStoryboardGenerator;
import com.harudle.generation.adapter.out.gemini.GeminiStoryboardResponseMapper;
import com.harudle.generation.adapter.out.s3.ImageObjectKeyFactory;
import com.harudle.generation.adapter.out.s3.S3ExceptionTranslator;
import com.harudle.generation.adapter.out.s3.S3FailureReporter;
import com.harudle.generation.adapter.out.s3.S3ImageStorage;
import com.harudle.generation.adapter.out.s3.S3ImageUrlProvider;
import com.harudle.generation.service.port.DiaryImageGenerator;
import com.harudle.generation.service.port.ImageStorage;
import com.harudle.generation.service.port.ImageUrlProvider;
import com.harudle.generation.service.port.StoryboardGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "harudle.generation.adapters",
        name = "enabled",
        havingValue = "true"
)
@EnableConfigurationProperties({GeminiGenerationProperties.class, S3StorageProperties.class})
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

    @Bean(destroyMethod = "close")
    public S3Presigner s3Presigner(S3StorageProperties properties) {
        return S3Presigner.builder()
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
    public GeminiFailureReporter geminiFailureReporter(
            GeminiExceptionTranslator exceptionTranslator,
            ExternalApiLogger externalApiLogger
    ) {
        return new GeminiFailureReporter(exceptionTranslator, externalApiLogger);
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
            GeminiFailureReporter failureReporter
    ) {
        return new GeminiStoryboardGenerator(
                geminiModels,
                properties,
                objectMapper,
                responseMapper,
                failureReporter
        );
    }

    @Bean
    public DiaryImageGenerator diaryImageGenerator(
            Models geminiModels,
            GeminiGenerationProperties properties,
            DiaryImagePromptRenderer promptRenderer,
            GeminiFailureReporter failureReporter
    ) {
        return new GeminiDiaryImageGenerator(
                geminiModels,
                properties,
                promptRenderer,
                failureReporter
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
    public S3FailureReporter s3FailureReporter(
            S3ExceptionTranslator exceptionTranslator,
            ExternalApiLogger externalApiLogger
    ) {
        return new S3FailureReporter(exceptionTranslator, externalApiLogger);
    }

    @Bean
    public ImageStorage imageStorage(
            S3Client s3Client,
            S3StorageProperties properties,
            ImageObjectKeyFactory objectKeyFactory,
            S3FailureReporter failureReporter
    ) {
        return new S3ImageStorage(
                s3Client,
                properties,
                objectKeyFactory,
                failureReporter
        );
    }

    @Bean
    public ImageUrlProvider imageUrlProvider(
            S3Presigner s3Presigner,
            S3StorageProperties properties,
            S3FailureReporter failureReporter
    ) {
        return new S3ImageUrlProvider(s3Presigner, properties, failureReporter);
    }
}
