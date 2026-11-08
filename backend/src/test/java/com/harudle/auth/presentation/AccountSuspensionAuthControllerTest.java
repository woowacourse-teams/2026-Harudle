package com.harudle.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.auth.application.AccountSuspendedException;
import com.harudle.auth.application.AuthService;
import com.harudle.auth.infrastructure.token.RefreshTokenCookieReader;
import com.harudle.auth.infrastructure.token.RefreshTokenCookieWriter;
import com.harudle.common.error.ErrorType;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.security.AuthProperties;
import com.harudle.common.security.LegacyCsrfCookieCleaner;
import com.harudle.common.security.RefreshTokenProperties;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

class AccountSuspensionAuthControllerTest {
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");

    @AfterEach
    void tearDown() {
        RestAssuredMockMvc.reset();
    }

    @Test
    @DisplayName("정지된 refresh 요청은 고정 403과 no-store를 반환하고 refresh 쿠키를 정리한다")
    void clearsRefreshCookieWhenSuspended() {
        AuthService auth = mock(AuthService.class);
        AuthProperties properties = mock(AuthProperties.class);
        when(properties.refreshToken()).thenReturn(new RefreshTokenProperties("refresh_token", "/api/v1/auth",
                false, "Lax", Duration.ofDays(14)));
        ProblemDetailFactory problems = mock(ProblemDetailFactory.class);
        var controller = new AuthController(auth, new RefreshTokenCookieReader(properties),
                new RefreshTokenCookieWriter(properties), CookieCsrfTokenRepository.withHttpOnlyFalse(), problems,
                new LegacyCsrfCookieCleaner(), Clock.fixed(NOW, ZoneOffset.UTC));
        when(auth.refresh("raw-token", NOW)).thenThrow(new AccountSuspendedException());
        RestAssuredMockMvc.standaloneSetup(controller);

        var response = RestAssuredMockMvc.given().postProcessors(request -> {
            request.setCookies(new Cookie("refresh_token", "raw-token"));
            return request;
        }).post("/api/v1/auth/refresh");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.header(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.header(HttpHeaders.SET_COOKIE)).contains("refresh_token=", "Max-Age=0", "HttpOnly");
        verify(problems).create(eq(ErrorType.ACCOUNT_SUSPENDED), any());
    }
}
