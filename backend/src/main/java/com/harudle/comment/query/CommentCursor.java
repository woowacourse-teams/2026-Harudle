package com.harudle.comment.query;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 삭제된 경계 댓글을 다시 조회하지 않아도 이어 볼 수 있는 정렬 경계. */
public record CommentCursor(UUID feedId, Instant createdAt, UUID id) {

    public CommentCursor {
        Objects.requireNonNull(feedId, "feedId는 필수입니다.");
        Objects.requireNonNull(createdAt, "createdAt은 필수입니다.");
        Objects.requireNonNull(id, "id는 필수입니다.");
    }
}
