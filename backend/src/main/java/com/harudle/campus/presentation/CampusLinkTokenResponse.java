package com.harudle.campus.presentation;

import com.harudle.campus.application.IssuedCampusLinkToken;
import java.net.URI;
import java.time.Instant;

public record CampusLinkTokenResponse(
        String token,
        URI redirectUri,
        Instant expiresAt
) {

    public static CampusLinkTokenResponse from(IssuedCampusLinkToken issued) {
        return new CampusLinkTokenResponse(issued.token(), issued.redirectUri(), issued.expiresAt());
    }
}
