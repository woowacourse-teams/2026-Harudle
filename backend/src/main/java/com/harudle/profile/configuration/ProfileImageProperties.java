package com.harudle.profile.configuration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("harudle.profile")
public record ProfileImageProperties(
        @NotNull URI imageBaseUrl
) {

    @AssertTrue(message = "프로필 이미지 기준 주소는 인증 정보, 쿼리, fragment가 없는 절대 HTTP(S) URL이어야 합니다.")
    public boolean isValidImageBaseUrl() {
        if (imageBaseUrl == null
                || !imageBaseUrl.isAbsolute()
                || imageBaseUrl.isOpaque()
                || imageBaseUrl.getHost() == null
                || imageBaseUrl.getUserInfo() != null
                || imageBaseUrl.getQuery() != null
                || imageBaseUrl.getFragment() != null) {
            return false;
        }
        return "http".equalsIgnoreCase(imageBaseUrl.getScheme())
                || "https".equalsIgnoreCase(imageBaseUrl.getScheme());
    }
}
