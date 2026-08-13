package com.harudle.common.security;

import java.time.Duration;

public record RefreshTokenProperties(
        String cookieName,
        String cookiePath,
        boolean secure,
        String sameSite,
        Duration ttl
) {
}
