package com.harudle.common.security;

import java.time.Duration;

public record AccessTokenProperties(
        String issuer,
        String audience,
        String secretBase64,
        Duration ttl
) {
}
