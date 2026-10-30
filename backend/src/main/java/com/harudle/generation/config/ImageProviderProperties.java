package com.harudle.generation.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("harudle.generation.image")
public record ImageProviderProperties(
        @Pattern(regexp = "none|openai") String fallbackProvider,
        String openaiApiKey,
        @Pattern(regexp = "gpt-image-2[.]5-flare(-2026-09-08)?") String openaiModel,
        Duration openaiTimeout,
        @Pattern(regexp = "low|medium|high|xhigh|max|auto") String openaiQuality
) {
    public ImageProviderProperties {
        fallbackProvider = fallbackProvider == null ? "none" : fallbackProvider;
        openaiModel = openaiModel == null ? "gpt-image-2.5-flare" : openaiModel;
        openaiTimeout = openaiTimeout == null ? Duration.ofSeconds(180) : openaiTimeout;
        openaiQuality = openaiQuality == null ? "medium" : openaiQuality;
    }

    public boolean openaiEnabled() {
        return "openai".equals(fallbackProvider);
    }

    @AssertTrue(message = "OpenAI 이미지 대체 생성에는 공백 없는 API 키가 필요합니다.")
    public boolean isOpenaiConnectionValid() {
        return !openaiEnabled() || openaiApiKey != null && !openaiApiKey.isBlank()
                && openaiApiKey.chars().allMatch(value -> value >= 33 && value <= 126);
    }

    @AssertTrue(message = "OpenAI 이미지 제한 시간은 1초 이상, 5분 이하여야 합니다.")
    public boolean isOpenaiTimeoutValid() {
        return !openaiEnabled() || openaiTimeout.compareTo(Duration.ofSeconds(1)) >= 0
                && openaiTimeout.compareTo(Duration.ofMinutes(5)) <= 0;
    }

    @Override
    public String toString() {
        return "ImageProviderProperties[fallbackProvider=" + fallbackProvider
                + ", openaiApiKey=[redacted], openaiModel=" + openaiModel
                + ", openaiTimeout=" + openaiTimeout
                + ", openaiQuality=" + openaiQuality + "]";
    }
}
