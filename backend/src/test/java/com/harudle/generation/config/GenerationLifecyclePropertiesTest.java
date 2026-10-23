package com.harudle.generation.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

class GenerationLifecyclePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues("spring.config.location=classpath:/application.yml", "spring.config.import=")
            .withInitializer(context -> {
                context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                new ConfigDataApplicationContextInitializer().initialize(context);
            });

    @Test
    @DisplayName("application.yml의 생성 시간 기본값과 대기 제한 120초, 대기 정리 주기 5초를 읽는다")
    void bindGenerationLifecycleDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            GenerationLifecycleProperties properties = context.getBean(GenerationLifecycleProperties.class);
            assertThat(properties.processingTimeout()).isEqualTo(Duration.ofMinutes(30));
            assertThat(properties.cleanupInterval()).isEqualTo(Duration.ofMinutes(1));
            assertThat(properties.pendingTimeout()).isEqualTo(Duration.ofSeconds(120));
            assertThat(properties.pendingCleanupInterval()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    @DisplayName("환경 변수로 처리 시간과 대기 시간을 각각 설정한다")
    void bindGenerationLifecycleEnvironmentVariables() {
        contextRunner.withPropertyValues(
                "GENERATION_PROCESSING_TIMEOUT=20m",
                "GENERATION_CLEANUP_INTERVAL=30s",
                "GENERATION_PENDING_TIMEOUT=90s",
                "GENERATION_PENDING_CLEANUP_INTERVAL=10s"
        ).run(context -> {
            assertThat(context).hasNotFailed();

            GenerationLifecycleProperties properties = context.getBean(GenerationLifecycleProperties.class);
            assertThat(properties.processingTimeout()).isEqualTo(Duration.ofMinutes(20));
            assertThat(properties.cleanupInterval()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.pendingTimeout()).isEqualTo(Duration.ofSeconds(90));
            assertThat(properties.pendingCleanupInterval()).isEqualTo(Duration.ofSeconds(10));
        });
    }

    @Test
    @DisplayName("그림일기 생성 처리 제한 시간이 양수가 아니면 설정 바인딩에 실패한다")
    void rejectNonPositiveProcessingTimeout() {
        contextRunner.withPropertyValues(
                "harudle.generation.lifecycle.processing-timeout=0s"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("그림일기 생성 정리 주기가 양수가 아니면 설정 바인딩에 실패한다")
    void rejectNonPositiveCleanupInterval() {
        contextRunner.withPropertyValues(
                "harudle.generation.lifecycle.cleanup-interval=0s"
        ).run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "GENERATION_PENDING_TIMEOUT=0s",
            "GENERATION_PENDING_TIMEOUT=-1s",
            "GENERATION_PENDING_TIMEOUT=",
            "GENERATION_PENDING_TIMEOUT=invalid",
            "GENERATION_PENDING_CLEANUP_INTERVAL=0s",
            "GENERATION_PENDING_CLEANUP_INTERVAL=-1s",
            "GENERATION_PENDING_CLEANUP_INTERVAL=1ns",
            "GENERATION_PENDING_CLEANUP_INTERVAL=",
            "GENERATION_PENDING_CLEANUP_INTERVAL=invalid"
    })
    @DisplayName("대기 제한 시간과 대기 정리 주기에 잘못된 값이 들어오면 서버 시작을 거절한다")
    void rejectInvalidPendingSettings(String setting) {
        contextRunner.withPropertyValues(setting)
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GenerationLifecycleProperties.class)
    static class TestConfiguration {
    }
}
