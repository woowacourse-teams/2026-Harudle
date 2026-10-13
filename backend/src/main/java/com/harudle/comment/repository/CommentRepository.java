package com.harudle.comment.repository;

import com.harudle.comment.query.CommentCursor;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommentRepository {

    boolean isActiveMember(UUID userId);

    boolean existsVisibleFeed(UUID feedId);

    /** 최초 조회와 피드 잠금 후 재조회에 사용한다. 댓글을 먼저 잠그지 않는다. */
    Optional<CommentSnapshot> findActiveById(UUID commentId);

    CommentSnapshot create(UUID commentId, UUID feedId, UUID authorId, String content, Instant createdAt);

    boolean delete(UUID commentId, UUID feedId, Instant deletedAt);

    List<CommentSnapshot> findAfter(UUID feedId, Optional<CommentCursor> cursor, int limit);
}
