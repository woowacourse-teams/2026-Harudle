package com.harudle.campus.application;

import java.net.URI;
import java.time.Instant;

public record IssuedCampusLinkToken(
        String token,
        URI redirectUri,
        Instant expiresAt
) {
}
