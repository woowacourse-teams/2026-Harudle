package com.harudle.generation.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class GeminiGenerationPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    @DisplayName("Gemini 생성 설정을 바인딩한다")
    void bindGeminiGenerationProperties() {
        contextRunner.withPropertyValues(
                "harudle.generation.gemini.api-key=test-api-key",
                "harudle.generation.gemini.storyboard-model=storyboard-model",
                "harudle.generation.gemini.image-model=image-model",
                "harudle.generation.gemini.storyboard-thinking-level=high",
                "harudle.generation.gemini.image-aspect-ratio=1:1",
                "harudle.generation.gemini.max-output-tokens=4096",
                "harudle.generation.gemini.retry-attempts=3",
                "harudle.generation.gemini.request-timeout=180s"
        ).run(context -> {
            assertThat(context).hasNotFailed();

            GeminiGenerationProperties properties = context.getBean(GeminiGenerationProperties.class);
            assertThat(properties.apiKey()).isEqualTo("test-api-key");
            assertThat(properties.storyboardModel()).isEqualTo("storyboard-model");
            assertThat(properties.imageModel()).isEqualTo("image-model");
            assertThat(properties.storyboardThinkingLevel()).isEqualTo("high");
            assertThat(properties.imageAspectRatio()).isEqualTo("1:1");
            assertThat(properties.maxOutputTokens()).isEqualTo(4096);
            assertThat(properties.retryAttempts()).isEqualTo(3);
            assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(180));
            assertThat(properties.toString())
                    .contains("apiKey=***")
                    .doesNotContain("test-api-key");
        });
    }

    @Test
    @DisplayName("Gemini API Key가 비어 있으면 설정 바인딩에 실패한다")
    void rejectBlankApiKey() {
        contextRunner.withPropertyValues(
                "harudle.generation.gemini.api-key= ",
                "harudle.generation.gemini.storyboard-model=storyboard-model",
                "harudle.generation.gemini.image-model=image-model",
                "harudle.generation.gemini.storyboard-thinking-level=high",
                "harudle.generation.gemini.image-aspect-ratio=1:1",
                "harudle.generation.gemini.max-output-tokens=4096",
                "harudle.generation.gemini.retry-attempts=3",
                "harudle.generation.gemini.request-timeout=180s"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("harudle.generation.gemini");
        });
    }

    @Test
    @DisplayName("Gemini 요청 제한 시간이 양수가 아니면 설정 바인딩에 실패한다")
    void rejectNonPositiveRequestTimeout() {
        contextRunner.withPropertyValues(
                "harudle.generation.gemini.api-key=test-api-key",
                "harudle.generation.gemini.storyboard-model=storyboard-model",
                "harudle.generation.gemini.image-model=image-model",
                "harudle.generation.gemini.storyboard-thinking-level=high",
                "harudle.generation.gemini.image-aspect-ratio=1:1",
                "harudle.generation.gemini.max-output-tokens=4096",
                "harudle.generation.gemini.retry-attempts=3",
                "harudle.generation.gemini.request-timeout=0s"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GeminiGenerationProperties.class)
    static class TestConfiguration {
    }
}
