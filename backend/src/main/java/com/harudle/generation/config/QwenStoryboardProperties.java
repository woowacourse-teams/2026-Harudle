package com.harudle.generation.config;

import com.harudle.generation.adapter.out.qwen.QwenStoryboardSettings;
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
@ConfigurationProperties("harudle.generation.qwen")
public record QwenStoryboardProperties(
        @Nullable String apiKey,
        @DefaultValue("qwen3.8-max") @NotBlank String storyboardModel,
        @DefaultValue("1024") @Min(1) int thinkingBudget,
        @DefaultValue("8192") @Min(1) int maxOutputTokens,
        @DefaultValue("180s") @NotNull Duration requestTimeout
) {

    @AssertTrue(message = "Qwen Token Plan에는 공백 없는 sk-sp- 전용 키가 필요합니다.")
    public boolean isApiKeyValid() {
        return apiKey != null && apiKey.length() <= 8192 && apiKey.matches("sk-sp-[\\x21-\\x7E]+");
    }

    @AssertTrue(message = "Qwen 요청 제한 시간은 1ms 이상, 2147483647ms 이하여야 합니다.")
    public boolean isRequestTimeoutValid() {
        return requestTimeout != null && requestTimeout.compareTo(Duration.ofMillis(1)) >= 0
                && requestTimeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) <= 0;
    }

    public QwenStoryboardSettings settings() {
        return new QwenStoryboardSettings(storyboardModel, maxOutputTokens, true, thinkingBudget, false,
                requestTimeout);
    }

    @Override
    public @NonNull String toString() {
        return ("QwenStoryboardProperties[apiKey=***, storyboardModel=%s, thinkingBudget=%s, "
                + "maxOutputTokens=%s, requestTimeout=%s]")
                .formatted(storyboardModel, thinkingBudget, maxOutputTokens, requestTimeout);
    }
}
