package com.harudle.guest.infrastructure.cookie;

import com.harudle.guest.configuration.GuestSessionCookieProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class GuestSessionCookieReader {

    private final String cookieName;

    public GuestSessionCookieReader(GuestSessionCookieProperties properties) {
        this.cookieName = properties.name();
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
}
