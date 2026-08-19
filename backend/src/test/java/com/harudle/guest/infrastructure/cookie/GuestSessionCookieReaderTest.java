package com.harudle.guest.infrastructure.cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.guest.configuration.GuestSessionCookieProperties;
import jakarta.servlet.http.Cookie;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class GuestSessionCookieReaderTest {

    private GuestSessionCookieReader cookieReader;

    @BeforeEach
    void setUp() {
        cookieReader = new GuestSessionCookieReader(createProperties());
    }

    @Test
    @DisplayName("설정된 이름의 게스트 세션 Cookie를 읽는다")
    void readsGuestSessionCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie("other_cookie", "other-value"),
                new Cookie("guest_session", "raw-guest-session-token")
        );

        Optional<String> rawToken = cookieReader.read(request);

        assertThat(rawToken).contains("raw-guest-session-token");
    }

    @Test
    @DisplayName("Cookie가 없으면 빈 값을 반환한다")
    void returnsEmptyWhenCookiesAreMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(cookieReader.read(request)).isEmpty();
    }

    @Test
    @DisplayName("게스트 세션 Cookie가 없으면 빈 값을 반환한다")
    void returnsEmptyWhenGuestSessionCookieIsMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("other_cookie", "other-value"));

        assertThat(cookieReader.read(request)).isEmpty();
    }

    @Test
    @DisplayName("빈 게스트 세션 Cookie는 읽지 않는다")
    void returnsEmptyWhenGuestSessionCookieIsBlank() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("guest_session", "   "));

        assertThat(cookieReader.read(request)).isEmpty();
    }

    @Test
    @DisplayName("요청이 없으면 게스트 세션 Cookie를 읽을 수 없다")
    void rejectsMissingRequest() {
        assertThatThrownBy(() -> cookieReader.read(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("request는 필수입니다.");
    }

    private GuestSessionCookieProperties createProperties() {
        return new GuestSessionCookieProperties(
                "guest_session",
                "/api/v1/guest",
                false,
                "Lax"
        );
    }
}
