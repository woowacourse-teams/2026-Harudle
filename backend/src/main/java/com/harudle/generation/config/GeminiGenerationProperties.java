package com.harudle.generation.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("harudle.generation.gemini")
public record GeminiGenerationProperties(
        @NotBlank String apiKey,
        @NotBlank String storyboardModel,
        @NotBlank String imageModel,
        @NotBlank
        @Pattern(regexp = "minimal|low|medium|high")
        String storyboardThinkingLevel,
        @NotBlank
        @Pattern(regexp = "1:1|3:2|2:3|3:4|4:3|4:5|5:4|9:16|16:9|21:9")
        String imageAspectRatio,
        @Min(1) int maxOutputTokens,
        @Min(1) int retryAttempts,
        @NotNull Duration requestTimeout
) {

    @AssertTrue(message = "Gemini 요청 제한 시간은 양수여야 합니다.")
    public boolean isRequestTimeoutPositive() {
        return requestTimeout != null && !requestTimeout.isZero() && !requestTimeout.isNegative();
    }

    @Override
    public @NonNull String toString() {
        return ("GeminiGenerationProperties[apiKey=***, storyboardModel=%s, imageModel=%s, "
                + "storyboardThinkingLevel=%s, imageAspectRatio=%s, maxOutputTokens=%d, "
                + "retryAttempts=%d, requestTimeout=%s]").formatted(
                storyboardModel,
                imageModel,
                storyboardThinkingLevel,
                imageAspectRatio,
                maxOutputTokens,
                retryAttempts,
                requestTimeout
        );
    }
}
