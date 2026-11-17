package com.harudle.generation.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class OpenAiStoryboardPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues("harudle.generation.openai-storyboard.api-key=sk-proj-test_key-123");

    @Test
    @DisplayName("OpenAI 스토리보드 기본 설정은 gpt-6-luna와 추론 medium 및 전체 호출 180초다")
    void bindDefaultStoryboardSettings() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(OpenAiStoryboardProperties.class);
            assertThat(properties.storyboardModel()).isEqualTo("gpt-6-luna");
            assertThat(properties.reasoningEffort()).isEqualTo("medium");
            assertThat(properties.maxOutputTokens()).isEqualTo(8192);
            assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(180));
        });
    }

    @Test
    @DisplayName("모델과 추론 강도 및 출력 한도와 제한 시간을 설정값으로 바꿀 수 있다")
    void bindCustomStoryboardSettings() {
        contextRunner.withPropertyValues(
                "harudle.generation.openai-storyboard.storyboard-model=gpt-6-luna-2026-11-01",
                "harudle.generation.openai-storyboard.reasoning-effort=low",
                "harudle.generation.openai-storyboard.max-output-tokens=4096",
                "harudle.generation.openai-storyboard.request-timeout=90s"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(OpenAiStoryboardProperties.class);
            assertThat(properties.storyboardModel()).isEqualTo("gpt-6-luna-2026-11-01");
            assertThat(properties.reasoningEffort()).isEqualTo("low");
            assertThat(properties.maxOutputTokens()).isEqualTo(4096);
            assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(90));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"max-output-tokens=0", "max-output-tokens=-1",
            "request-timeout=0s", "request-timeout=-1s", "request-timeout=1ns",
            "request-timeout=2147483648ms", "storyboard-model=", "reasoning-effort=",
            "api-key=", "api-key=sk-has space"})
    @DisplayName("잘못된 출력 한도와 제한 시간 및 모델과 키 설정은 시작 시 거절한다")
    void rejectInvalidStoryboardSettings(String setting) {
        contextRunner.withPropertyValues("harudle.generation.openai-storyboard." + setting)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("API 키가 없으면 시작 시 거절한다")
    void rejectMissingApiKey() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfiguration.class)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("키를 검증해도 바인딩 오류와 설정 문자열에는 원문 키를 노출하지 않는다")
    void redactApiKeyInBindingFailuresAndPropertyStrings() {
        String privateKey = "sk-secret invalid-openai-key";
        contextRunner.withPropertyValues("harudle.generation.openai-storyboard.api-key=" + privateKey)
                .run(context -> {
                    assertThat(context).hasFailed();
                    StringWriter errorText = new StringWriter();
                    context.getStartupFailure().printStackTrace(new PrintWriter(errorText));
                    assertThat(errorText.toString()).doesNotContain(privateKey);
                });
        var properties = new OpenAiStoryboardProperties(privateKey, "gpt-6-luna", "medium", 8192,
                Duration.ofSeconds(180));
        assertThat(properties.toString()).contains("apiKey=***").doesNotContain(privateKey);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OpenAiStoryboardProperties.class)
    static class TestConfiguration {
    }
}
