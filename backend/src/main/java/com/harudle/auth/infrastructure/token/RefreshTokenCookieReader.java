package com.harudle.auth.infrastructure.token;

import com.harudle.common.security.AuthProperties;
import com.harudle.common.security.RefreshTokenProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class RefreshTokenCookieReader {

    private final String cookieName;

    public RefreshTokenCookieReader(AuthProperties authProperties) {
        this.cookieName = extractCookieName(authProperties);
    }

    public Optional<String> read(HttpServletRequest request) {
        Objects.requireNonNull(request, "request는 필수입니다.");

        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }

        for (Cookie cookie : cookies) {
            if (!cookieName.equals(cookie.getName())) {
                continue;
            }

            String value = cookie.getValue();
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }

            return Optional.of(value);
        }

        return Optional.empty();
    }

    private String extractCookieName(AuthProperties authProperties) {
        Objects.requireNonNull(authProperties, "authProperties는 필수입니다.");

        RefreshTokenProperties properties = Objects.requireNonNull(
                authProperties.refreshToken(),
                "refreshToken 설정은 필수입니다."
        );

        String name = properties.cookieName();
        if (name != null && !name.isBlank()) {
            return name;
        }

        throw new IllegalArgumentException("refreshToken cookieName은 필수입니다.");
    }

}
