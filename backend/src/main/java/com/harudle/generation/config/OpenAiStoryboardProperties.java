package com.harudle.generation.config;

import com.harudle.generation.adapter.out.openai.OpenAiStoryboardSettings;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("harudle.generation.openai-storyboard")
public record OpenAiStoryboardProperties(
        @Nullable String apiKey,
        @DefaultValue("gpt-6-luna") @NotBlank String storyboardModel,
        @DefaultValue("medium") @NotBlank String reasoningEffort,
        @DefaultValue("8192") @Min(1) int maxOutputTokens,
        @DefaultValue("180s") @NotNull Duration requestTimeout
) {

    @AssertTrue(message = "OpenAI 스토리보드 생성에는 공백 없는 API 키가 필요합니다.")
    public boolean isApiKeyValid() {
        return apiKey != null && !apiKey.isEmpty() && apiKey.length() <= 8192
                && apiKey.chars().allMatch(value -> value >= 0x21 && value <= 0x7E);
    }

    @AssertTrue(message = "OpenAI 스토리보드 요청 제한 시간은 1ms 이상, 2147483647ms 이하여야 합니다.")
    public boolean isRequestTimeoutValid() {
        return requestTimeout != null && requestTimeout.compareTo(Duration.ofMillis(1)) >= 0
                && requestTimeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) <= 0;
    }

    public OpenAiStoryboardSettings settings() {
        return new OpenAiStoryboardSettings(storyboardModel, maxOutputTokens, reasoningEffort, false, requestTimeout);
    }

    @Override
    public @NonNull String toString() {
        return ("OpenAiStoryboardProperties[apiKey=***, storyboardModel=%s, reasoningEffort=%s, "
                + "maxOutputTokens=%s, requestTimeout=%s]")
                .formatted(storyboardModel, reasoningEffort, maxOutputTokens, requestTimeout);
    }
}
