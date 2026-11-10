package com.harudle.generation.config;

import com.google.genai.Client;
import com.google.genai.Models;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.harudle.auth.infrastructure.S3ProfileImageStorage;
import com.harudle.generation.adapter.out.gemini.GeminiFailureReporter;
import com.harudle.generation.adapter.out.gemini.GeminiStageMetrics;
import com.harudle.generation.adapter.out.gemini.GeminiStoryboardGenerator;
import com.harudle.generation.adapter.out.gemini.client.GeminiClientFactory;
import com.harudle.generation.adapter.out.gemini.client.GeminiStoryboardCallScope;
import com.harudle.generation.adapter.out.http.SingleAttemptHttpTransport;
import com.harudle.generation.adapter.out.s3.ObservedImageUrlProvider;
import com.harudle.generation.adapter.out.s3.R2FallbackImageUrlProvider;
import com.harudle.generation.adapter.out.s3.S3FailureReporter;
import com.harudle.generation.adapter.out.s3.S3ImageUrlProvider;
import com.harudle.generation.adapter.out.storyboard.StoryboardJsonCodec;
import com.harudle.generation.adapter.out.storyboard.StoryboardResponseMapper;
import com.harudle.generation.diary.service.port.BackupObjectStorage;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.ImageUrlProvider;
import com.harudle.generation.diary.service.port.StoryboardGenerator;
import io.micrometer.core.instrument.MeterRegistry;
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
@Import({QwenStoryboardConfiguration.class, StoryboardGenerationConfiguration.class,
        ImageStorageConfiguration.class, ImageGenerationConfiguration.class})
@EnableConfigurationProperties({S3StorageProperties.class, StoryboardProviderProperties.class})
public class GenerationAdapterConfiguration {

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
    public S3Presigner s3Presigner(S3StorageProperties properties) {
        return S3Presigner.builder()
                .region(Region.of(properties.region()))
                .build();
    }

    @Bean
    public S3ProfileImageStorage profileImageStorage(
            @Qualifier("s3Client") S3Client s3Client,
            @Qualifier("s3Presigner") S3Presigner s3Presigner,
            S3StorageProperties properties
    ) {
        return new S3ProfileImageStorage(s3Client, s3Presigner, properties);
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
