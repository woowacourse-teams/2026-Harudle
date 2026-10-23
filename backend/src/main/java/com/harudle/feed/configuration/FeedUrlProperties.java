package com.harudle.feed.configuration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("harudle.feed")
public record FeedUrlProperties(@NotNull URI publicBaseUrl) {

    @AssertTrue(message = "피드 공유 기준 주소는 쿼리와 fragment가 없는 절대 HTTP(S) URL이어야 합니다.")
    public boolean isValidPublicBaseUrl() {
        return publicBaseUrl != null && publicBaseUrl.isAbsolute() && !publicBaseUrl.isOpaque()
                && publicBaseUrl.getHost() != null
                && publicBaseUrl.getRawQuery() == null && publicBaseUrl.getRawFragment() == null
                && ("https".equalsIgnoreCase(publicBaseUrl.getScheme())
                || "http".equalsIgnoreCase(publicBaseUrl.getScheme()));
    }

    public URI shareUrl(UUID feedId) {
        String base = publicBaseUrl.toString().replaceAll("/+$", "");
        return URI.create(base + "/" + feedId);
    }
}
