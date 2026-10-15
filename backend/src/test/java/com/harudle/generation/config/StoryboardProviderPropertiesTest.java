package com.harudle.generation.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

class StoryboardProviderPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    @DisplayName("기본값은 Gemini 단독 선택과 메인 및 보조 60초다")
    void bindDefaultsWithoutEnablingFallback() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(StoryboardProviderProperties.class);
            assertThat(properties.provider()).isEqualTo("gemini");
            assertThat(properties.fallbackProvider()).isEqualTo("none");
            assertThat(properties.fallbackEnabled()).isFalse();
            assertThat(properties.primaryTimeout()).isEqualTo(Duration.ofSeconds(60));
            assertThat(properties.fallbackTimeout()).isEqualTo(Duration.ofSeconds(60));
            assertThat(properties.totalTimeout()).isEqualTo(Duration.ofSeconds(60));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"gemini", "qwen"})
    @DisplayName("기존 두 Provider의 단독 선택을 유지한다")
    void preserveSingleProviderSelection(String provider) {
        contextRunner.withPropertyValues("harudle.generation.storyboard.provider=" + provider).run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(StoryboardProviderProperties.class);
            assertThat(properties.provider()).isEqualTo(provider);
            assertThat(properties.fallbackEnabled()).isFalse();
        });
    }

    @ParameterizedTest
    @CsvSource({"gemini,qwen", "qwen,gemini"})
    @DisplayName("서로 다른 메인과 보조를 선택하면 기본 전체 예산은 120초다")
    void bindDistinctProviders(String primary, String fallback) {
        contextRunner.withPropertyValues("harudle.generation.storyboard.provider=" + primary,
                "harudle.generation.storyboard.fallback-provider=" + fallback).run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(StoryboardProviderProperties.class);
            assertThat(properties.provider()).isEqualTo(primary);
            assertThat(properties.fallbackProvider()).isEqualTo(fallback);
            assertThat(properties.fallbackEnabled()).isTrue();
            assertThat(properties.totalTimeout()).isEqualTo(Duration.ofSeconds(120));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"gemini", "qwen"})
    @DisplayName("메인과 보조에 같은 Provider를 지정하면 시작 시 거절한다")
    void rejectSameProviderPair(String provider) {
        contextRunner.withPropertyValues("harudle.generation.storyboard.provider=" + provider,
                "harudle.generation.storyboard.fallback-provider=" + provider)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"provider=", "provider=Gemini", "provider=unknown", "fallback-provider=",
            "fallback-provider=Qwen", "fallback-provider=unknown", "primary-timeout=0s", "primary-timeout=-1s",
            "primary-timeout=1ns", "primary-timeout=2147483648ms", "primary-timeout=invalid",
            "fallback-timeout=0s", "fallback-timeout=-1s", "fallback-timeout=1ns",
            "fallback-timeout=2147483648ms", "fallback-timeout=invalid"})
    @DisplayName("지원하지 않는 Provider와 유효 범위 밖 제한 시간은 시작 시 거절한다")
    void rejectInvalidSettings(String setting) {
        contextRunner.withPropertyValues("harudle.generation.storyboard." + setting)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1ms", "2147483647ms"})
    @DisplayName("HTTP 제한 시간의 최소와 최대 경계값을 허용한다")
    void acceptTimeoutBoundaries(String timeout) {
        contextRunner.withPropertyValues("harudle.generation.storyboard.primary-timeout=" + timeout,
                "harudle.generation.storyboard.fallback-timeout=" + timeout)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("환경 변수는 application.yml을 통해 선택과 제한 시간을 바인딩하며 기존 HTTP 설정은 유지한다")
    void bindEnvironmentVariablesThroughApplicationYaml() {
        contextRunner.withPropertyValues("STORYBOARD_PROVIDER=gemini", "STORYBOARD_FALLBACK_PROVIDER=qwen",
                "STORYBOARD_PRIMARY_TIMEOUT=45s", "STORYBOARD_FALLBACK_TIMEOUT=50s")
                .withInitializer(context -> {
                    try {
                        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                                .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                }).run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(StoryboardProviderProperties.class);
                    assertThat(properties.provider()).isEqualTo("gemini");
                    assertThat(properties.fallbackProvider()).isEqualTo("qwen");
                    assertThat(properties.primaryTimeout()).isEqualTo(Duration.ofSeconds(45));
                    assertThat(properties.fallbackTimeout()).isEqualTo(Duration.ofSeconds(50));
                    assertThat(properties.totalTimeout()).isEqualTo(Duration.ofSeconds(95));
                    assertThat(context.getEnvironment().getProperty("harudle.generation.gemini.request-timeout"))
                            .isEqualTo("180s");
                    assertThat(context.getEnvironment().getProperty("harudle.generation.qwen.request-timeout"))
                            .isEqualTo("180s");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(StoryboardProviderProperties.class)
    static class TestConfiguration {
    }
}
