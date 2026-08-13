package com.harudle.auth.infrastructure.token;

import com.harudle.auth.application.IssuedRefreshToken;
import com.harudle.common.security.AuthProperties;
import com.harudle.common.security.RefreshTokenProperties;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class RefreshTokenCookieWriter {

    private final RefreshTokenProperties refreshTokenProperties;

    public RefreshTokenCookieWriter(AuthProperties authProperties) {
        this.refreshTokenProperties = extractRefreshTokenProperties(authProperties);
    }

    public void write(HttpServletResponse response, IssuedRefreshToken issuedRefreshToken) {
        Objects.requireNonNull(response, "response는 필수입니다.");
        Objects.requireNonNull(issuedRefreshToken, "issuedRefreshToken은 필수입니다.");

        addCookie(
                response,
                issuedRefreshToken.rawToken(),
                refreshTokenProperties.ttl()
        );
    }

    public void clear(HttpServletResponse response) {
        Objects.requireNonNull(response, "response는 필수입니다.");

        addCookie(response, "", Duration.ZERO);
    }

    private void addCookie(
            HttpServletResponse response,
            String value,
            Duration maxAge
    ) {
        ResponseCookie cookie = ResponseCookie.from(
                        refreshTokenProperties.cookieName(),
                        value
                )
                .httpOnly(true)
                .secure(refreshTokenProperties.secure())
                .path(refreshTokenProperties.cookiePath())
                .sameSite(refreshTokenProperties.sameSite())
                .maxAge(maxAge)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private RefreshTokenProperties extractRefreshTokenProperties(AuthProperties authProperties) {
        Objects.requireNonNull(authProperties, "authProperties는 필수입니다.");

        RefreshTokenProperties properties = Objects.requireNonNull(
                authProperties.refreshToken(),
                "refreshToken 설정은 필수입니다."
        );
        validateProperties(properties);

        return properties;
    }

    private void validateProperties(RefreshTokenProperties properties) {
        validateRequiredText(properties.cookieName(), "refreshToken cookieName");
        validateRequiredText(properties.cookiePath(), "refreshToken cookiePath");
        validateRequiredText(properties.sameSite(), "refreshToken sameSite");

        Duration ttl = Objects.requireNonNull(
                properties.ttl(),
                "refreshToken ttl은 필수입니다."
        );
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("refreshToken ttl은 양수여야 합니다.");
        }
    }

    private void validateRequiredText(String value, String fieldName) {
        if (value != null && !value.isBlank()) {
            return;
        }

        throw new IllegalArgumentException(fieldName + "은 필수입니다.");
    }

}
