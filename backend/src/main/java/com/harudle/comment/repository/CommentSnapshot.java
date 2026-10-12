package com.harudle.comment.repository;

import java.time.Instant;
import java.util.UUID;

public record CommentSnapshot(UUID id, UUID feedId, UUID authorId, String content, Instant createdAt) {}
