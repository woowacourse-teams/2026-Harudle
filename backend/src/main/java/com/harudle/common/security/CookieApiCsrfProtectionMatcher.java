package com.harudle.common.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

public final class CookieApiCsrfProtectionMatcher implements RequestMatcher {

    private static final Set<String> COOKIE_API_PATHS = Set.of(
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/guest/session",
            "/api/v1/guest/diaries"
    );

    private final RequestMatcher cookieApiMatcher = new OrRequestMatcher(COOKIE_API_PATHS.stream()
            .<RequestMatcher>map(path -> PathPatternRequestMatcher.withDefaults().matcher(path))
            .toList());

    @Override
    public boolean matches(HttpServletRequest request) {
        return CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request) && cookieApiMatcher.matches(request);
    }

    public static boolean isCookieApi(String path) {
        return COOKIE_API_PATHS.contains(path);
    }
}
