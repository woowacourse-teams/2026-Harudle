package com.harudle.notification.presentation;

import com.harudle.profile.service.port.PublicProfileReader.Profile;
import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record NotificationActorResponse(
        UUID id,
        String nickname,
        URI profileImageUrl,
        @Schema(description = "개인 프로필 이미지 URL 만료 시각. 기본 이미지는 null",
                type = "string", format = "date-time", nullable = true)
        @Nullable Instant profileImageUrlExpiresAt
) {
    static NotificationActorResponse from(Profile actor) {
        return new NotificationActorResponse(
                actor.id(), actor.nickname(), actor.profileImageUrl(), actor.profileImageUrlExpiresAt()
        );
    }
}
