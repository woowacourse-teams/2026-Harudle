package com.harudle.campus.presentation;

import com.harudle.campus.application.CampusLinkProfile;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record CampusLinkProfileResponse(
        UUID userId,
        String nickname,
        @Nullable URI profileImageUrl,
        @Nullable Instant profileImageUrlExpiresAt
) {

    public static CampusLinkProfileResponse from(CampusLinkProfile profile) {
        return new CampusLinkProfileResponse(
                profile.userId(),
                profile.nickname(),
                profile.profileImageUrl(),
                profile.profileImageUrlExpiresAt()
        );
    }
}
