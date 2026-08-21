package com.harudle.common.security;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public final class LegacyCsrfCookieCleaner {

    private static final String LEGACY_CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final String LEGACY_CSRF_COOKIE_PATH = "/api/v1/auth";

    public void clear(HttpServletResponse response) {
        Objects.requireNonNull(response, "response는 필수입니다.");

        ResponseCookie expiredCookie = ResponseCookie
                .from(LEGACY_CSRF_COOKIE_NAME, "")
                .path(LEGACY_CSRF_COOKIE_PATH)
                .maxAge(Duration.ZERO)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, expiredCookie.toString());
    }
}
