package com.harudle.generation.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.Models;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.adapter.out.codex.CodexDiaryImageGenerator;
import com.harudle.generation.adapter.out.gemini.DiaryImagePromptRenderer;
import com.harudle.generation.adapter.out.gemini.GeminiDiaryImageGenerator;
import com.harudle.generation.adapter.out.gemini.GeminiExceptionTranslator;
import com.harudle.generation.adapter.out.gemini.GeminiFailureReporter;
import com.harudle.generation.adapter.out.gemini.GeminiStageMetrics;
import com.harudle.generation.adapter.out.gemini.GeminiStoryboardGenerator;
import com.harudle.generation.adapter.out.gemini.client.ExpressGeminiClientFactory;
import com.harudle.generation.adapter.out.gemini.client.GeminiClientFactory;
import com.harudle.generation.adapter.out.gemini.client.GeminiStoryboardCallScope;
import com.harudle.generation.adapter.out.gemini.client.VertexGeminiClientFactory;
import com.harudle.generation.adapter.out.http.SingleAttemptHttpTransport;
import com.harudle.generation.adapter.out.s3.CwebpImageVariantEncoder;
import com.harudle.generation.adapter.out.s3.ImageObjectKeyFactory;
import com.harudle.generation.adapter.out.s3.ImageUploadPreparer;
import com.harudle.generation.adapter.out.s3.ImageVariantEncoder;
import com.harudle.generation.adapter.out.s3.ObservedImageStorage;
import com.harudle.generation.adapter.out.s3.ObservedImageUrlProvider;
import com.harudle.generation.adapter.out.s3.R2FallbackImageUrlProvider;
import com.harudle.generation.adapter.out.s3.S3ExceptionTranslator;
import com.harudle.generation.adapter.out.s3.S3FailureReporter;
import com.harudle.generation.adapter.out.s3.S3ImageStorage;
import com.harudle.generation.adapter.out.s3.S3ImageUrlProvider;
import com.harudle.generation.adapter.out.storyboard.StoryboardJsonCodec;
import com.harudle.generation.adapter.out.storyboard.StoryboardResponseMapper;
import com.harudle.generation.diary.service.image.FallbackDiaryImageGenerator;
import com.harudle.generation.diary.service.port.BackupObjectStorage;
import com.harudle.generation.diary.service.port.DiaryImageGenerator;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.ImageUrlProvider;
import com.harudle.generation.diary.service.port.StoryboardGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
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
@Import({QwenStoryboardConfiguration.class, StoryboardGenerationConfiguration.class})
@EnableConfigurationProperties({GeminiClientProperties.class, GeminiGenerationProperties.class,
        S3StorageProperties.class, StoryboardProviderProperties.class, ImageProviderProperties.class})
public class GenerationAdapterConfiguration {

    @Bean
    public GeminiClientFactory geminiClientFactory(
            GeminiClientProperties properties,
            @Qualifier("geminiCredentials") ObjectProvider<GoogleCredentials> credentialsProvider
    ) throws IOException {
        return switch (properties.authMode()) {
            case EXPRESS -> new ExpressGeminiClientFactory(properties.apiKey());
            case VERTEX -> {
                GoogleCredentials credentials = credentialsProvider.getIfAvailable();
                if (credentials == null) {
                    credentials = GoogleCredentials.getApplicationDefault();
                }
                yield new VertexGeminiClientFactory(properties.projectId(), properties.location(), credentials);
            }
        };
    }

    @Bean(destroyMethod = "close")
    public Client geminiClient(
            GeminiGenerationProperties properties,
            GeminiClientFactory clientFactory,
            ImageProviderProperties imageProperties
    ) {
        int requestTimeoutMillis = Math.toIntExact(properties.requestTimeout().toMillis());
        HttpRetryOptions retryOptions = imageProperties.codexEnabled()
                ? HttpRetryOptions.builder().attempts(1).httpStatusCodes(List.of()).build()
                : HttpRetryOptions.builder().attempts(properties.retryAttempts()).build();
        HttpOptions httpOptions = HttpOptions.builder()
                .timeout(requestTimeoutMillis)
                .retryOptions(retryOptions)
                .build();

        if (imageProperties.codexEnabled()) {
            ClientOptions clientOptions = ClientOptions.builder().customHttpClient(
                    SingleAttemptHttpTransport.builder(properties.requestTimeout()).build()
            ).build();
            return clientFactory.create(httpOptions, clientOptions);
        }
        return clientFactory.create(httpOptions);
    }

    @Bean
    public Models geminiModels(@Qualifier("geminiClient") Client geminiClient) {
        return geminiClient.models;
    }

    @Bean
    @Conditional(GeminiStoryboardSelectedCondition.class)
    public GeminiStoryboardCallScope geminiStoryboardCallScope() {
        return new GeminiStoryboardCallScope();
    }

    @Bean(destroyMethod = "close")
    @Conditional(GeminiStoryboardSelectedCondition.class)
    public Client geminiStoryboardClient(
            StoryboardProviderProperties properties,
            GeminiClientFactory clientFactory,
            GeminiStoryboardCallScope callScope
    ) {
        Duration timeout = properties.timeoutFor("gemini");
        HttpOptions httpOptions = HttpOptions.builder()
                .timeout(Math.toIntExact(timeout.toMillis()))
                .retryOptions(HttpRetryOptions.builder().attempts(1).httpStatusCodes(List.of()).build())
                .build();
        ClientOptions clientOptions = ClientOptions.builder().customHttpClient(
                SingleAttemptHttpTransport.builder(timeout).eventListenerFactory(callScope).build()
        ).build();
        return clientFactory.create(httpOptions, clientOptions);
    }

    @Bean
    @Conditional(GeminiStoryboardSelectedCondition.class)
    public Models geminiStoryboardModels(@Qualifier("geminiStoryboardClient") Client client) {
        return client.models;
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
    public StoryboardResponseMapper storyboardResponseMapper() {
        return new StoryboardResponseMapper();
    }

    @Bean
    public StoryboardJsonCodec storyboardJsonCodec(ObjectMapper objectMapper, StoryboardResponseMapper responseMapper) {
        return new StoryboardJsonCodec(objectMapper, responseMapper);
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
    @Conditional(GeminiStoryboardSelectedCondition.class)
    public StoryboardGenerator geminiStoryboardGenerator(
            @Qualifier("geminiStoryboardModels") Models geminiModels,
            GeminiGenerationProperties properties,
            ObjectMapper objectMapper,
            StoryboardResponseMapper responseMapper,
            GeminiFailureReporter failureReporter,
            GeminiStageMetrics stageMetrics,
            GeminiStoryboardCallScope callScope
    ) {
        return new GeminiStoryboardGenerator(
                geminiModels,
                properties,
                objectMapper,
                responseMapper,
                failureReporter,
                stageMetrics,
                callScope
        );
    }

    @Bean
    public DiaryImageGenerator diaryImageGenerator(
            @Qualifier("geminiModels") Models geminiModels,
            GeminiGenerationProperties properties,
            DiaryImagePromptRenderer promptRenderer,
            GeminiFailureReporter failureReporter,
            GeminiStageMetrics stageMetrics,
            ImageProviderProperties imageProperties,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry
    ) {
        DiaryImageGenerator primary = new GeminiDiaryImageGenerator(
                geminiModels, properties, promptRenderer, failureReporter, stageMetrics
        );
        if (!imageProperties.codexEnabled()) {
            return primary;
        }
        DiaryImageGenerator fallback = new CodexDiaryImageGenerator(
                imageProperties, promptRenderer, objectMapper, meterRegistry
        );
        return new FallbackDiaryImageGenerator(primary, fallback, meterRegistry);
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
