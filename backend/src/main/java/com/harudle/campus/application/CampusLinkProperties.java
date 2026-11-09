package com.harudle.campus.application;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 우테코 캠퍼스 연결 설정. 환경 변수 HARUDLE_CAMPUS_ENABLED, HARUDLE_CAMPUS_REDIRECT_URI,
 * HARUDLE_CAMPUS_SIGNING_KEY_BASE64로 바로 읽으므로 application.yml을 바꾸지 않는다.
 *
 * @param enabled 연결 기능 사용 여부. 꺼져 있으면 연결 API를 등록하지 않는다
 * @param redirectUri 연결 토큰을 받을 캠퍼스 콜백 주소. 요청으로 받지 않고 이 값만 사용한다
 * @param signingKeyBase64 캠퍼스와 나눠 가진 HS256 서명 키(32바이트 이상, Base64)
 * @param tokenTtl 연결 토큰 유효 시간
 */
@ConfigurationProperties(prefix = "harudle.campus")
public record CampusLinkProperties(
        boolean enabled,
        URI redirectUri,
        String signingKeyBase64,
        Duration tokenTtl
) {

    private static final Duration DEFAULT_TOKEN_TTL = Duration.ofSeconds(60);
    private static final int MINIMUM_KEY_LENGTH = 32;

    public CampusLinkProperties {
        if (tokenTtl == null || tokenTtl.isZero() || tokenTtl.isNegative()) {
            tokenTtl = DEFAULT_TOKEN_TTL;
        }
        if (enabled) {
            validateEnabled(redirectUri, signingKeyBase64);
        }
    }

    public byte[] signingKey() {
        return Base64.getDecoder().decode(signingKeyBase64);
    }

    private static void validateEnabled(URI redirectUri, String signingKeyBase64) {
        if (redirectUri == null || !redirectUri.isAbsolute()) {
            throw new IllegalArgumentException("harudle.campus.redirect-uri는 절대 주소여야 합니다.");
        }
        if (signingKeyBase64 == null || decodedLength(signingKeyBase64) < MINIMUM_KEY_LENGTH) {
            throw new IllegalArgumentException("harudle.campus.signing-key-base64는 32바이트 이상의 Base64 값이어야 합니다.");
        }
    }

    private static int decodedLength(String value) {
        try {
            return Base64.getDecoder().decode(value).length;
        } catch (IllegalArgumentException exception) {
            return 0;
        }
    }
}
