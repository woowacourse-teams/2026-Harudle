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

class QwenStoryboardPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues("harudle.generation.qwen.api-key=sk-sp-test-key.with+ascii==");

    @Test
    @DisplayName("Qwen 기본 설정은 Max 모델과 추론 1024 및 전체 호출 180초다")
    void bindDefaultStoryboardSettings() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var settings = context.getBean(QwenStoryboardProperties.class).settings();
            assertThat(settings.model()).isEqualTo("qwen3.8-max");
            assertThat(settings.thinking()).isTrue();
            assertThat(settings.thinkingBudget()).isEqualTo(1024);
            assertThat(settings.maxOutputTokens()).isEqualTo(8192);
            assertThat(settings.strictSchema()).isFalse();
            assertThat(settings.requestTimeout()).isEqualTo(Duration.ofSeconds(180));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"thinking-budget=0", "thinking-budget=-1", "max-output-tokens=0",
            "request-timeout=0s", "request-timeout=-1s", "request-timeout=1ns",
            "request-timeout=2147483648ms", "storyboard-model=", "api-key="})
    @DisplayName("잘못된 출력 한도와 추론 및 키 설정은 시작 시 거절한다")
    void rejectInvalidStoryboardSettings(String setting) {
        contextRunner.withPropertyValues("harudle.generation.qwen." + setting)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("키를 검증해도 바인딩 오류와 설정 문자열에는 원문 키를 노출하지 않는다")
    void redactTokenPlanKeyInBindingFailuresAndPropertyStrings() {
        String privateKey = "sk-secret-invalid-token-plan-key";
        contextRunner.withPropertyValues("harudle.generation.qwen.api-key=" + privateKey).run(context -> {
            assertThat(context).hasFailed();
            StringWriter errorText = new StringWriter();
            context.getStartupFailure().printStackTrace(new PrintWriter(errorText));
            assertThat(errorText.toString()).doesNotContain(privateKey);
        });
        var properties = new QwenStoryboardProperties(privateKey, "qwen3.8-max", 1024, 8192,
                Duration.ofSeconds(180));
        assertThat(properties.toString()).contains("apiKey=***").doesNotContain(privateKey);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(QwenStoryboardProperties.class)
    static class TestConfiguration {
    }
}
