package com.harudle.campus.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CampusLinkPropertiesTest {

    private static final String KEY = "Y2FtcHVzLXNpZ25pbmcta2V5LWZvci10ZXN0LTAxMjM0NTY3ODk=";

    @ParameterizedTest
    @ValueSource(strings = {
            "https://campus.harudle.com/auth/harudle/callback",
            "https://campus.harudle.com:8443/auth/harudle/callback",
            "http://localhost:3000/auth/harudle/callback",
            "http://LOCALHOST:3000/auth/harudle/callback",
            "http://127.0.0.1:3000/auth/harudle/callback"
    })
    @DisplayName("HTTPS 콜백과 정확한 로컬 주소의 HTTP 콜백을 허용한다")
    void acceptsSecureOrLocalCallback(String redirectUri) {
        assertThatCode(() -> properties(redirectUri)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/auth/harudle/callback",
            "//campus.harudle.com/auth/harudle/callback",
            "http://campus.harudle.com/auth/harudle/callback",
            "http://localhost.example.com/auth/harudle/callback",
            "http://127.0.0.1.example.com/auth/harudle/callback",
            "http://0.0.0.0:3000/auth/harudle/callback",
            "http://localhost@campus.harudle.com/auth/harudle/callback",
            "ftp://campus.harudle.com/auth/harudle/callback",
            "file:///auth/harudle/callback",
            "javascript:alert(1)",
            "https:campus.harudle.com/auth/harudle/callback",
            "https:///auth/harudle/callback",
            "https://user:password@campus.harudle.com/auth/harudle/callback",
            "https://campus.harudle.com/auth/harudle/callback#fragment"
    })
    @DisplayName("외부 HTTP와 호스트가 없거나 사용자 정보 또는 fragment가 있는 콜백은 거절한다")
    void rejectsUnsafeCallback(String redirectUri) {
        assertThatThrownBy(() -> properties(redirectUri)).isInstanceOf(IllegalArgumentException.class);
    }

    private CampusLinkProperties properties(String redirectUri) {
        return new CampusLinkProperties(true, URI.create(redirectUri), KEY, null);
    }
}
