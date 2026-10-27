package com.harudle.generation.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class GeminiClientPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    @DisplayName("기본 Express 인증을 바인딩하고 API Key를 출력에서 가린다")
    void bindExpressByDefault() {
        contextRunner.withPropertyValues("harudle.generation.gemini.api-key=test-api-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GeminiClientProperties properties = context.getBean(GeminiClientProperties.class);
                    assertThat(properties.authMode()).isEqualTo(GeminiClientProperties.AuthMode.EXPRESS);
                    assertThat(properties.apiKey()).isEqualTo("test-api-key");
                    assertThat(properties.toString()).contains("apiKey=***").doesNotContain("test-api-key");
                });
    }

    @Test
    @DisplayName("Express 모드에서 API Key가 없으면 시작에 실패한다")
    void rejectMissingExpressApiKey() {
        contextRunner.run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    @DisplayName("Express 모드에서 공백 API Key를 거부한다")
    void rejectBlankExpressApiKey(String apiKey) {
        contextRunner.withPropertyValues("harudle.generation.gemini.api-key=" + apiKey)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("Vertex 모드는 API Key 없이 프로젝트를 바인딩하며 기본 위치는 global이다")
    void bindVertexWithoutApiKey() {
        contextRunner.withPropertyValues(
                "harudle.generation.gemini.auth-mode=vertex",
                "harudle.generation.gemini.project-id=test-project"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            GeminiClientProperties properties = context.getBean(GeminiClientProperties.class);
            assertThat(properties.authMode()).isEqualTo(GeminiClientProperties.AuthMode.VERTEX);
            assertThat(properties.apiKey()).isNull();
            assertThat(properties.projectId()).isEqualTo("test-project");
            assertThat(properties.location()).isEqualTo("global");
        });
    }

    @Test
    @DisplayName("Vertex 모드에서 프로젝트가 없으면 시작에 실패한다")
    void rejectMissingVertexProject() {
        contextRunner.withPropertyValues("harudle.generation.gemini.auth-mode=vertex")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("Vertex 모드에서 공백 위치를 거부한다")
    void rejectBlankLocation() {
        contextRunner.withPropertyValues(
                "harudle.generation.gemini.auth-mode=vertex",
                "harudle.generation.gemini.project-id=test-project",
                "harudle.generation.gemini.location= "
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("지원하지 않는 인증 모드로 시작할 수 없다")
    void rejectUnknownAuthMode() {
        contextRunner.withPropertyValues("harudle.generation.gemini.auth-mode=unknown")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GeminiClientProperties.class)
    static class TestConfiguration {
    }
}
