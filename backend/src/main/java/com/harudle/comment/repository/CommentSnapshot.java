package com.harudle.comment.repository;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record CommentSnapshot(
        UUID id, UUID feedId, @Nullable UUID authorId, String content, Instant createdAt, boolean authorDeleted
) {
    public CommentSnapshot(UUID id, UUID feedId, @Nullable UUID authorId, String content, Instant createdAt) {
        this(id, feedId, authorId, content, createdAt, false);
    }
}
