package com.harudle.guest.infrastructure.cookie;

import com.harudle.guest.application.IssuedGuestSession;
import com.harudle.guest.configuration.GuestSessionCookieProperties;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class GuestSessionCookieWriter {

    private final GuestSessionCookieProperties properties;

    public GuestSessionCookieWriter(GuestSessionCookieProperties properties) {
        this.properties = properties;
    }

    public void write(
            HttpServletResponse response,
            IssuedGuestSession issuedSession,
            Instant now
    ) {
        Objects.requireNonNull(response, "response는 필수입니다.");
        Objects.requireNonNull(issuedSession, "issuedSession은 필수입니다.");
        Objects.requireNonNull(now, "now는 필수입니다.");

        Duration remainingTtl = calculateRemainingTtl(issuedSession, now);

        ResponseCookie cookie = ResponseCookie.from(
                        properties.name(),
                        issuedSession.rawToken()
                )
                .httpOnly(true)
                .secure(properties.secure())
                .path(properties.path())
                .sameSite(properties.sameSite())
                .maxAge(remainingTtl)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private Duration calculateRemainingTtl(
            IssuedGuestSession issuedSession,
            Instant now
    ) {
        Duration remainingTtl = Duration.between(now, issuedSession.expiresAt());
        if (remainingTtl.isZero() || remainingTtl.isNegative()) {
            throw new IllegalArgumentException("만료된 게스트 세션은 Cookie로 저장할 수 없습니다.");
        }

        return remainingTtl;
    }
}
