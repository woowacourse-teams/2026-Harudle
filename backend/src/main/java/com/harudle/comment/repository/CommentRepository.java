package com.harudle.comment.repository;

import com.harudle.comment.query.CommentCursor;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface CommentRepository {

    /** 회원 삭제 전 같은 트랜잭션에서 차단 상대의 댓글 숨김을 보존한다. */
    void preserveBlockedVisibility(UUID authorId);

    boolean isActiveMember(UUID userId);

    boolean existsVisibleFeed(UUID feedId, @Nullable UUID viewerId);

    /** 최초 조회와 피드 잠금 후 재조회에 사용한다. 댓글을 먼저 잠그지 않는다. */
    Optional<CommentSnapshot> findActiveById(UUID commentId);

    CommentSnapshot create(UUID commentId, UUID feedId, UUID authorId, String content, Instant createdAt);

    boolean delete(UUID commentId, UUID feedId, Instant deletedAt);

    List<CommentSnapshot> findAfter(UUID feedId, Optional<CommentCursor> cursor, int limit, @Nullable UUID viewerId);
}
