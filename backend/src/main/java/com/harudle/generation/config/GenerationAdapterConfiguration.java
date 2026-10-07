package com.harudle.generation.config;

import com.google.auth.oauth2.GoogleCredentials;
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
import com.harudle.generation.adapter.out.gemini.GeminiStageMetrics;
import com.harudle.generation.adapter.out.gemini.GeminiStoryboardResponseMapper;
import com.harudle.generation.adapter.out.gemini.client.ExpressGeminiClientFactory;
import com.harudle.generation.adapter.out.gemini.client.GeminiClientFactory;
import com.harudle.generation.adapter.out.gemini.client.VertexGeminiClientFactory;
import com.harudle.generation.adapter.out.s3.CwebpImageVariantEncoder;
import com.harudle.generation.adapter.out.s3.ImageObjectKeyFactory;
import com.harudle.generation.adapter.out.s3.ImageVariantEncoder;
import com.harudle.generation.adapter.out.s3.ImageUploadPreparer;
import com.harudle.generation.adapter.out.s3.ObservedImageStorage;
import com.harudle.generation.adapter.out.s3.ObservedImageUrlProvider;
import com.harudle.generation.adapter.out.s3.S3ExceptionTranslator;
import com.harudle.generation.adapter.out.s3.S3FailureReporter;
import com.harudle.generation.adapter.out.s3.S3ImageStorage;
import com.harudle.generation.adapter.out.s3.S3ImageUrlProvider;
import com.harudle.generation.adapter.out.s3.R2FallbackImageUrlProvider;
import com.harudle.generation.diary.service.port.BackupObjectStorage;
import com.harudle.generation.diary.service.port.DiaryImageGenerator;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.ImageUrlProvider;
import com.harudle.generation.diary.service.port.StoryboardGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
@EnableConfigurationProperties({GeminiClientProperties.class, GeminiGenerationProperties.class, S3StorageProperties.class})
public class GenerationAdapterConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "harudle.generation.gemini", name = "auth-mode",
            havingValue = "express", matchIfMissing = true)
    public GeminiClientFactory expressGeminiClientFactory(GeminiClientProperties properties) {
        return new ExpressGeminiClientFactory(properties.apiKey());
    }

    @Bean
    @ConditionalOnProperty(prefix = "harudle.generation.gemini", name = "auth-mode", havingValue = "vertex")
    @ConditionalOnMissingBean(name = "geminiCredentials")
    public GoogleCredentials geminiCredentials() throws IOException {
        return GoogleCredentials.getApplicationDefault();
    }

    @Bean
    @ConditionalOnProperty(prefix = "harudle.generation.gemini", name = "auth-mode", havingValue = "vertex")
    public GeminiClientFactory vertexGeminiClientFactory(
            GeminiClientProperties properties,
            @Qualifier("geminiCredentials") GoogleCredentials credentials
    ) {
        return new VertexGeminiClientFactory(properties.projectId(), properties.location(), credentials);
    }

    @Bean(destroyMethod = "close")
    public Client geminiClient(GeminiGenerationProperties properties, GeminiClientFactory clientFactory) {
        int requestTimeoutMillis = Math.toIntExact(properties.requestTimeout().toMillis());
        HttpRetryOptions retryOptions = HttpRetryOptions.builder()
                .attempts(properties.retryAttempts())
                .build();
        HttpOptions httpOptions = HttpOptions.builder()
                .timeout(requestTimeoutMillis)
                .retryOptions(retryOptions)
                .build();

        return clientFactory.create(httpOptions);
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
    public GeminiStageMetrics geminiStageMetrics(MeterRegistry meterRegistry) {
        return new GeminiStageMetrics(meterRegistry);
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
            GeminiFailureReporter failureReporter,
            GeminiStageMetrics stageMetrics
    ) {
        return new GeminiStoryboardGenerator(
                geminiModels,
                properties,
                objectMapper,
                responseMapper,
                failureReporter,
                stageMetrics
        );
    }

    @Bean
    public DiaryImageGenerator diaryImageGenerator(
            Models geminiModels,
            GeminiGenerationProperties properties,
            DiaryImagePromptRenderer promptRenderer,
            GeminiFailureReporter failureReporter,
            GeminiStageMetrics stageMetrics
    ) {
        return new GeminiDiaryImageGenerator(
                geminiModels,
                properties,
                promptRenderer,
                failureReporter,
                stageMetrics
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
    public ImageVariantEncoder imageVariantEncoder() {
        CwebpImageVariantEncoder encoder = new CwebpImageVariantEncoder();
        encoder.verifyAvailable();
        return encoder;
    }

    @Bean
    public ImageUploadPreparer imageUploadPreparer(
            ImageObjectKeyFactory objectKeyFactory,
            ImageVariantEncoder variantEncoder
    ) {
        return new ImageUploadPreparer(objectKeyFactory, variantEncoder);
    }

    @Bean
    public ImageStorage imageStorage(
            @Qualifier("s3Client") S3Client s3Client,
            S3StorageProperties properties,
            ImageUploadPreparer uploadPreparer,
            S3FailureReporter failureReporter,
            MeterRegistry meterRegistry
    ) {
        ImageStorage storage = new S3ImageStorage(
                s3Client,
                properties,
                uploadPreparer,
                failureReporter
        );
        return new ObservedImageStorage(storage, meterRegistry);
    }

    @Bean
    public ImageUrlProvider imageUrlProvider(
            @Qualifier("s3Presigner") S3Presigner s3Presigner,
            S3StorageProperties properties,
            S3FailureReporter failureReporter,
            MeterRegistry meterRegistry,
            @Qualifier("imageStorage") ImageStorage imageStorage,
            ObjectProvider<BackupObjectStorage> backupStorages,
            ObjectProvider<R2StorageProperties> backupProperties
    ) {
        ImageUrlProvider primary = new ObservedImageUrlProvider(
                new S3ImageUrlProvider(s3Presigner, properties, failureReporter),
                meterRegistry
        );
        BackupObjectStorage backup = backupStorages.getIfAvailable();
        if (backup == null) {
            return primary;
        }
        return new R2FallbackImageUrlProvider(primary, imageStorage, backup, properties,
                java.util.Objects.requireNonNull(backupProperties.getIfAvailable(), "R2 저장소 설정이 필요합니다."));
    }
}
