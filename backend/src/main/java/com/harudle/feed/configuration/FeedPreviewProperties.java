package com.harudle.feed.configuration;

import jakarta.validation.constraints.AssertTrue;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("harudle.feed.preview")
public record FeedPreviewProperties(@Nullable String imageBaseUrl) {

    @AssertTrue(message = "피드 미리보기 이미지 기준 주소는 인증 정보, 쿼리, fragment가 없는 절대 HTTP(S) URL이어야 합니다.")
    public boolean isValidImageBaseUrl() {
        if (imageBaseUrl == null || imageBaseUrl.isBlank()) {
            return true;
        }
        try {
            URI uri = URI.create(imageBaseUrl);
            return uri.isAbsolute() && !uri.isOpaque() && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
