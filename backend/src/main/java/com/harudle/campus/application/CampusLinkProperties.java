package com.harudle.campus.application;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 우테코 캠퍼스 연결 설정.
 *
 * @param enabled 연결 기능 사용 여부. 꺼져 있으면 두 API 모두 404를 반환한다
 * @param redirectUri 연결 코드를 받을 캠퍼스 콜백 주소. 요청으로 받지 않고 이 값만 사용한다
 * @param clientSecretSha256 캠퍼스 서버 비밀값의 SHA-256 해시(16진수 64자). 비밀값 원본은 두지 않는다
 * @param codeTtl 연결 코드 유효 시간
 */
@ConfigurationProperties(prefix = "harudle.campus")
public record CampusLinkProperties(
        boolean enabled,
        URI redirectUri,
        String clientSecretSha256,
        Duration codeTtl
) {

    private static final Duration DEFAULT_CODE_TTL = Duration.ofSeconds(60);

    public CampusLinkProperties {
        if (codeTtl == null || codeTtl.isZero() || codeTtl.isNegative()) {
            codeTtl = DEFAULT_CODE_TTL;
        }
        if (enabled) {
            validateEnabled(redirectUri, clientSecretSha256);
        }
    }

    private static void validateEnabled(URI redirectUri, String clientSecretSha256) {
        if (redirectUri == null || !redirectUri.isAbsolute()) {
            throw new IllegalArgumentException("harudle.campus.redirect-uri는 절대 주소여야 합니다.");
        }
        if (clientSecretSha256 == null || !clientSecretSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("harudle.campus.client-secret-sha256은 소문자 16진수 64자여야 합니다.");
        }
    }
}
