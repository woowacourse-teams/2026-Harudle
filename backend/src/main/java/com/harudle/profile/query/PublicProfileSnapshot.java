package com.harudle.profile.query;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record PublicProfileSnapshot(
        UUID id,
        String nickname,
        Short profileImageCode,
        @Nullable String profileImageObjectKey
) {
}
