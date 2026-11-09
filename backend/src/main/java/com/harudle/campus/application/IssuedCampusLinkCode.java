package com.harudle.campus.application;

import java.net.URI;
import java.time.Instant;

public record IssuedCampusLinkCode(
        String code,
        URI redirectUri,
        Instant expiresAt
) {
}
