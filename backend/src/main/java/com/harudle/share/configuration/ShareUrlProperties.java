package com.harudle.share.configuration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("harudle.share")
public record ShareUrlProperties(
        @NotNull URI publicBaseUrl
) {

    @AssertTrue(message = "공유 URL 기준 주소는 호스트가 있는 절대 HTTP(S) URL이어야 합니다.")
    public boolean isValidPublicBaseUrl() {
        if (publicBaseUrl == null
                || !publicBaseUrl.isAbsolute()
                || publicBaseUrl.isOpaque()
                || publicBaseUrl.getHost() == null) {
            return false;
        }
        return "http".equalsIgnoreCase(publicBaseUrl.getScheme())
                || "https".equalsIgnoreCase(publicBaseUrl.getScheme());
    }
}
