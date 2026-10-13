package com.harudle.notification.presentation;

import com.harudle.profile.service.port.PublicProfileReader.Profile;
import java.net.URI;
import java.util.UUID;

public record NotificationActorResponse(UUID id, String nickname, URI profileImageUrl) {
    static NotificationActorResponse from(Profile actor) {
        return new NotificationActorResponse(actor.id(), actor.nickname(), actor.profileImageUrl());
    }
}
