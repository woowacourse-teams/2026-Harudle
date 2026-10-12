package com.harudle.comment.presentation;

import com.harudle.profile.service.port.PublicProfileReader.Profile;
import java.net.URI;
import java.util.UUID;

public record CommentAuthorResponse(UUID id, String nickname, URI profileImageUrl) {
    static CommentAuthorResponse from(Profile profile) {
        return new CommentAuthorResponse(profile.id(), profile.nickname(), profile.profileImageUrl());
    }
}
