package com.harudle.campus.presentation;

import com.harudle.campus.application.IssuedCampusLinkCode;
import java.net.URI;
import java.time.Instant;

public record CampusLinkCodeResponse(
        String code,
        URI redirectUri,
        Instant expiresAt
) {

    public static CampusLinkCodeResponse from(IssuedCampusLinkCode issued) {
        return new CampusLinkCodeResponse(issued.code(), issued.redirectUri(), issued.expiresAt());
    }
}
