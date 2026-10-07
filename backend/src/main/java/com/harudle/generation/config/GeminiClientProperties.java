package com.harudle.generation.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("harudle.generation.gemini")
public record GeminiClientProperties(
        @DefaultValue("express") @NotNull AuthMode authMode,
        @Nullable String apiKey,
        @Nullable String projectId,
        @DefaultValue("global") @NotBlank String location
) {

    @AssertTrue(message = "Gemini Express 모드에는 API Key가 필요합니다.")
    public boolean isExpressApiKeyPresent() {
        return authMode != AuthMode.EXPRESS || StringUtils.hasText(apiKey);
    }

    @AssertTrue(message = "Gemini Vertex 모드에는 GCP 프로젝트 ID가 필요합니다.")
    public boolean isVertexProjectPresent() {
        return authMode != AuthMode.VERTEX || StringUtils.hasText(projectId);
    }

    @Override
    public @NonNull String toString() {
        return "GeminiClientProperties[authMode=%s, apiKey=***, projectId=%s, location=%s]"
                .formatted(authMode, projectId, location);
    }

    public enum AuthMode {
        EXPRESS,
        VERTEX
    }
}
