package com.harudle.comment.service.dto;

import com.harudle.profile.service.port.PublicProfileReader.Profile;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CommentResult(UUID id, Profile author, String content, Instant createdAt, boolean isMine) {
    public CommentResult {
        Objects.requireNonNull(id);
        Objects.requireNonNull(author);
        Objects.requireNonNull(content);
        Objects.requireNonNull(createdAt);
    }
}
