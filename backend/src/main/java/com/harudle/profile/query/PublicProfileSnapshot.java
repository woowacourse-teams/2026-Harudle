package com.harudle.profile.query;

import java.util.UUID;

public record PublicProfileSnapshot(
        UUID id,
        String nickname,
        Short profileImageCode
) {
}
