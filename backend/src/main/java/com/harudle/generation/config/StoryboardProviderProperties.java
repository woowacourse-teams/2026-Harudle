package com.harudle.generation.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("harudle.generation.storyboard")
public record StoryboardProviderProperties(
        @DefaultValue("gemini") @NotBlank @Pattern(regexp = "gemini|qwen") String provider,
        @DefaultValue("none") @NotBlank @Pattern(regexp = "none|gemini|qwen") String fallbackProvider,
        @DefaultValue("60s") @NotNull Duration primaryTimeout,
        @DefaultValue("60s") @NotNull Duration fallbackTimeout
) {

    private static final Duration MIN_TIMEOUT = Duration.ofMillis(1);
    private static final Duration MAX_TIMEOUT = Duration.ofMillis(Integer.MAX_VALUE);

    public boolean fallbackEnabled() {
        return !"none".equals(fallbackProvider);
    }

    public Duration totalTimeout() {
        return fallbackEnabled() ? primaryTimeout.plus(fallbackTimeout) : primaryTimeout;
    }

    @AssertTrue(message = "메인과 보조 스토리보드 Provider는 서로 달라야 합니다.")
    public boolean isProviderPairValid() {
        return provider == null || fallbackProvider == null || !fallbackEnabled() || !provider.equals(fallbackProvider);
    }

    @AssertTrue(message = "메인 스토리보드 제한 시간은 1ms 이상, 2147483647ms 이하여야 합니다.")
    public boolean isPrimaryTimeoutValid() {
        return isTimeoutValid(primaryTimeout);
    }

    @AssertTrue(message = "보조 스토리보드 제한 시간은 1ms 이상, 2147483647ms 이하여야 합니다.")
    public boolean isFallbackTimeoutValid() {
        return isTimeoutValid(fallbackTimeout);
    }

    private static boolean isTimeoutValid(Duration timeout) {
        return timeout != null && timeout.compareTo(MIN_TIMEOUT) >= 0 && timeout.compareTo(MAX_TIMEOUT) <= 0;
    }
}
