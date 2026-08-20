package com.harudle.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.auth.application.AuthService;
import com.harudle.auth.application.CurrentUserService;
import com.harudle.auth.infrastructure.token.RefreshTokenCookieReader;
import com.harudle.auth.infrastructure.token.RefreshTokenCookieWriter;
import com.harudle.common.error.ErrorType;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.security.LegacyCsrfCookieCleaner;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

class AuthProblemDetailHandlingTest {

    @Test
    @DisplayName("Refresh Token 오류를 공통 Factory 응답으로 반환하고 Cookie를 삭제한다")
    void handleInvalidRefreshToken() {
        ProblemDetailFactory problemDetailFactory = mock(ProblemDetailFactory.class);
        RefreshTokenCookieWriter cookieWriter = mock(RefreshTokenCookieWriter.class);
        AuthController controller = new AuthController(
                mock(AuthService.class),
                mock(RefreshTokenCookieReader.class),
                cookieWriter,
                mock(CookieCsrfTokenRepository.class),
                problemDetailFactory,
                new LegacyCsrfCookieCleaner(),
                Clock.systemUTC()
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/refresh");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ProblemDetail problemDetail = ProblemDetail.forStatus(401);
        when(problemDetailFactory.create(ErrorType.INVALID_REFRESH_TOKEN, request))
                .thenReturn(problemDetail);

        ResponseEntity<ProblemDetail> result = controller.handleInvalidRefreshToken(request, response);

        assertThat(result.getStatusCode().value()).isEqualTo(401);
        assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(result.getBody()).isSameAs(problemDetail);
        verify(cookieWriter).clear(response);
    }

    @Test
    @DisplayName("현재 사용자 오류를 공통 Factory 응답으로 반환한다")
    void handleInvalidCurrentUser() {
        ProblemDetailFactory problemDetailFactory = mock(ProblemDetailFactory.class);
        CurrentUserController controller = new CurrentUserController(
                mock(CurrentUserService.class),
                problemDetailFactory
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        ProblemDetail problemDetail = ProblemDetail.forStatus(401);
        when(problemDetailFactory.create(ErrorType.INVALID_CURRENT_USER, request))
                .thenReturn(problemDetail);

        ResponseEntity<ProblemDetail> result = controller.handleInvalidCurrentUser(request);

        assertThat(result.getStatusCode().value()).isEqualTo(401);
        assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(result.getBody()).isSameAs(problemDetail);
    }
}
