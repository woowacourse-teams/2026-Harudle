package com.harudle.guest.infrastructure.cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.guest.application.IssuedGuestSession;
import com.harudle.guest.configuration.GuestSessionCookieProperties;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

class GuestSessionCookieWriterTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-08-19T00:00:00Z");
    private static final Duration SESSION_TTL = Duration.ofDays(30);

    @Test
    @DisplayName("게스트 세션 토큰을 보안 속성을 가진 HttpOnly Cookie로 저장한다")
    void writesGuestSessionCookie() {
        GuestSessionCookieWriter cookieWriter = new GuestSessionCookieWriter(
                createProperties(true)
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        IssuedGuestSession issuedSession = createIssuedSession();

        cookieWriter.write(response, issuedSession, ISSUED_AT);

        String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .contains("guest_session=raw-guest-session-token")
                .contains("Path=/api/v1/guest")
                .contains("Max-Age=2592000")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Lax");
    }

    @Test
    @DisplayName("기존 세션을 재사용하면 남은 유효 기간만 Cookie에 설정한다")
    void writesRemainingSessionTtl() {
        GuestSessionCookieWriter cookieWriter = new GuestSessionCookieWriter(
                createProperties(false)
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        IssuedGuestSession issuedSession = createIssuedSession();
        Instant reusedAt = ISSUED_AT.plus(Duration.ofDays(1));

        cookieWriter.write(response, issuedSession, reusedAt);

        String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .contains("Max-Age=2505600")
                .doesNotContain("Secure");
    }

    @Test
    @DisplayName("만료된 게스트 세션은 Cookie로 저장하지 않는다")
    void rejectsExpiredGuestSession() {
        GuestSessionCookieWriter cookieWriter = new GuestSessionCookieWriter(
                createProperties(false)
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        IssuedGuestSession issuedSession = createIssuedSession();

        assertThatThrownBy(() -> cookieWriter.write(
                response,
                issuedSession,
                issuedSession.expiresAt()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("만료된 게스트 세션은 Cookie로 저장할 수 없습니다.");
    }

    @Test
    @DisplayName("응답이나 발급 결과 또는 현재 시각이 없으면 Cookie를 저장할 수 없다")
    void rejectsMissingArguments() {
        GuestSessionCookieWriter cookieWriter = new GuestSessionCookieWriter(
                createProperties(false)
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        IssuedGuestSession issuedSession = createIssuedSession();

        assertThatThrownBy(() -> cookieWriter.write(null, issuedSession, ISSUED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("response는 필수입니다.");
        assertThatThrownBy(() -> cookieWriter.write(response, null, ISSUED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("issuedSession은 필수입니다.");
        assertThatThrownBy(() -> cookieWriter.write(response, issuedSession, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("now는 필수입니다.");
    }

    private IssuedGuestSession createIssuedSession() {
        return new IssuedGuestSession(
                "raw-guest-session-token",
                ISSUED_AT.plus(SESSION_TTL)
        );
    }

    private GuestSessionCookieProperties createProperties(boolean secure) {
        return new GuestSessionCookieProperties(
                "guest_session",
                "/api/v1/guest",
                secure,
                "Lax"
        );
    }
}
