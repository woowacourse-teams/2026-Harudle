package com.harudle.generation.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("harudle.generation.image")
public record ImageProviderProperties(
        @Pattern(regexp = "none|codex") String fallbackProvider,
        URI codexUrl,
        String codexToken,
        Duration codexTimeout
) {

    public ImageProviderProperties {
        fallbackProvider = fallbackProvider == null ? "none" : fallbackProvider;
        codexTimeout = codexTimeout == null ? Duration.ofSeconds(310) : codexTimeout;
    }

    public boolean codexEnabled() {
        return "codex".equals(fallbackProvider);
    }

    @AssertTrue(message = "Codex fallback에는 HTTP(S) worker URL과 32자 이상의 내부 인증 토큰이 필요합니다.")
    public boolean isCodexConnectionValid() {
        return !codexEnabled() || codexUrl != null
                && ("http".equals(codexUrl.getScheme()) || "https".equals(codexUrl.getScheme()))
                && codexUrl.getHost() != null && codexUrl.getUserInfo() == null
                && codexUrl.getQuery() == null && codexUrl.getFragment() == null
                && (codexUrl.getPath().isEmpty() || "/".equals(codexUrl.getPath()))
                && codexToken != null && codexToken.length() >= 32
                && codexToken.chars().allMatch(value -> value >= 33 && value <= 126);
    }

    @AssertTrue(message = "Codex 이미지 제한 시간은 1초 이상, 10분 이하여야 합니다.")
    public boolean isCodexTimeoutValid() {
        return !codexEnabled() || codexTimeout.compareTo(Duration.ofSeconds(1)) >= 0
                && codexTimeout.compareTo(Duration.ofMinutes(10)) <= 0;
    }

    @Override
    public String toString() {
        return "ImageProviderProperties[fallbackProvider=" + fallbackProvider
                + ", codexUrl=" + codexUrl + ", codexToken=[redacted], codexTimeout=" + codexTimeout + "]";
    }
}
