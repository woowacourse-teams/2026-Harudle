package com.harudle.generation.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class GenerationLifecyclePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    @DisplayName("그림일기 생성 생명주기 설정을 바인딩한다")
    void bindGenerationLifecycleProperties() {
        contextRunner.withPropertyValues(
                "harudle.generation.lifecycle.processing-timeout=30m",
                "harudle.generation.lifecycle.cleanup-interval=1m"
        ).run(context -> {
            assertThat(context).hasNotFailed();

            GenerationLifecycleProperties properties = context.getBean(GenerationLifecycleProperties.class);
            assertThat(properties.processingTimeout()).isEqualTo(Duration.ofMinutes(30));
            assertThat(properties.cleanupInterval()).isEqualTo(Duration.ofMinutes(1));
        });
    }

    @Test
    @DisplayName("그림일기 생성 처리 제한 시간이 양수가 아니면 설정 바인딩에 실패한다")
    void rejectNonPositiveProcessingTimeout() {
        contextRunner.withPropertyValues(
                "harudle.generation.lifecycle.processing-timeout=0s",
                "harudle.generation.lifecycle.cleanup-interval=1m"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("그림일기 생성 정리 주기가 양수가 아니면 설정 바인딩에 실패한다")
    void rejectNonPositiveCleanupInterval() {
        contextRunner.withPropertyValues(
                "harudle.generation.lifecycle.processing-timeout=30m",
                "harudle.generation.lifecycle.cleanup-interval=0s"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GenerationLifecycleProperties.class)
    static class TestConfiguration {
    }
}
