package com.harudle.generation.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.Models;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.adapter.out.gemini.DiaryImagePromptRenderer;
import com.harudle.generation.adapter.out.gemini.GeminiDiaryImageGenerator;
import com.harudle.generation.adapter.out.gemini.GeminiExceptionTranslator;
import com.harudle.generation.adapter.out.gemini.GeminiFailureReporter;
import com.harudle.generation.adapter.out.gemini.GeminiStageMetrics;
import com.harudle.generation.adapter.out.gemini.client.ExpressGeminiClientFactory;
import com.harudle.generation.adapter.out.gemini.client.GeminiClientFactory;
import com.harudle.generation.adapter.out.gemini.client.VertexGeminiClientFactory;
import com.harudle.generation.adapter.out.http.SingleAttemptHttpTransport;
import com.harudle.generation.adapter.out.openai.OpenAiDiaryImageGenerator;
import com.harudle.generation.diary.service.image.FallbackDiaryImageGenerator;
import com.harudle.generation.diary.service.port.DiaryImageGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Image generation from an existing storyboard does not need diary plaintext or its encryption keys. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "harudle.generation.adapters", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({GeminiClientProperties.class, GeminiGenerationProperties.class,
        ImageProviderProperties.class})
public class ImageGenerationConfiguration {

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
        HttpRetryOptions retryOptions = imageProperties.openaiFallbackEnabled()
                ? HttpRetryOptions.builder().attempts(1).httpStatusCodes(List.of()).build()
                : HttpRetryOptions.builder().attempts(properties.retryAttempts()).build();
        HttpOptions httpOptions = HttpOptions.builder()
                .timeout(requestTimeoutMillis)
                .retryOptions(retryOptions)
                .build();

        if (imageProperties.openaiFallbackEnabled()) {
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
        if ("openai".equals(imageProperties.provider())) {
            return new OpenAiDiaryImageGenerator(
                    imageProperties, properties.imageAspectRatio(), promptRenderer, objectMapper, meterRegistry
            );
        }
        DiaryImageGenerator primary = new GeminiDiaryImageGenerator(
                geminiModels, properties, promptRenderer, failureReporter, stageMetrics
        );
        if (!imageProperties.openaiFallbackEnabled()) {
            return primary;
        }
        DiaryImageGenerator fallback = new OpenAiDiaryImageGenerator(
                imageProperties, properties.imageAspectRatio(), promptRenderer, objectMapper, meterRegistry
        );
        return new FallbackDiaryImageGenerator(primary, fallback, meterRegistry);
    }

}
