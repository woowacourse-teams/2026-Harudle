package com.harudle.comment.infrastructure;

import com.harudle.comment.query.CommentCursor;
import com.harudle.comment.repository.CommentRepository;
import com.harudle.comment.repository.CommentSnapshot;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaCommentRepository implements CommentRepository {

    private static final String ACTIVE_MEMBER_QUERY = """
            SELECT count(*)
            FROM users u
            WHERE u.id = :userId
              AND u.deleted_at IS NULL
              AND EXISTS (SELECT 1 FROM oauth_accounts a WHERE a.user_id = u.id)
            """;
    private static final String VISIBLE_FEED_QUERY = """
            SELECT count(*)
            FROM feeds f
            JOIN diaries d ON d.id = f.diary_id
            JOIN users feed_author ON feed_author.id = d.user_id
            WHERE f.id = :feedId AND f.deleted_at IS NULL AND d.deleted_at IS NULL
              AND feed_author.deleted_at IS NULL
            """;
    private static final String FIND_ACTIVE_QUERY = """
            SELECT id, feed_id, author_id, content, created_at
            FROM comments
            WHERE id = :commentId AND deleted_at IS NULL
            """;
    private static final String CREATE_QUERY = """
            INSERT INTO comments (id, feed_id, author_id, content, created_at)
            VALUES (:commentId, :feedId, :authorId, :content, :createdAt)
            RETURNING id, feed_id, author_id, content, created_at
            """;
    private static final String DELETE_QUERY = """
            UPDATE comments
            SET deleted_at = :deletedAt
            WHERE id = :commentId AND feed_id = :feedId AND deleted_at IS NULL
            """;
    private static final String LIST_QUERY = """
            SELECT c.id, c.feed_id, c.author_id, c.content, c.created_at
            FROM comments c
            JOIN feeds f ON f.id = c.feed_id
            JOIN diaries d ON d.id = f.diary_id
            JOIN users feed_author ON feed_author.id = d.user_id
            WHERE c.feed_id = :feedId
              AND c.deleted_at IS NULL AND f.deleted_at IS NULL AND d.deleted_at IS NULL
              AND feed_author.deleted_at IS NULL
            """;
    private static final String AFTER_PREDICATE = """
              AND (c.created_at > :afterCreatedAt
                   OR (c.created_at = :afterCreatedAt AND c.id > :afterId))
            """;
    private static final String ORDER_QUERY = " ORDER BY c.created_at ASC, c.id ASC";

    private final EntityManager entityManager;

    JpaCommentRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActiveMember(UUID userId) {
        Objects.requireNonNull(userId, "userId는 필수입니다.");
        return count(ACTIVE_MEMBER_QUERY, "userId", userId) > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsVisibleFeed(UUID feedId) {
        Objects.requireNonNull(feedId, "feedId는 필수입니다.");
        return count(VISIBLE_FEED_QUERY, "feedId", feedId) > 0;
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Optional<CommentSnapshot> findActiveById(UUID commentId) {
        Objects.requireNonNull(commentId, "commentId는 필수입니다.");
        List<Object[]> rows = entityManager.createNativeQuery(FIND_ACTIVE_QUERY)
                .setParameter("commentId", commentId).getResultList();
        return rows.stream().map(JpaCommentRepository::snapshot).findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    @SuppressWarnings("unchecked")
    public CommentSnapshot create(UUID commentId, UUID feedId, UUID authorId, String content, Instant createdAt) {
        Objects.requireNonNull(commentId, "commentId는 필수입니다.");
        Objects.requireNonNull(feedId, "feedId는 필수입니다.");
        Objects.requireNonNull(authorId, "authorId는 필수입니다.");
        Objects.requireNonNull(content, "content는 필수입니다.");
        Objects.requireNonNull(createdAt, "createdAt은 필수입니다.");
        List<Object[]> rows = entityManager.createNativeQuery(CREATE_QUERY)
                .setParameter("commentId", commentId).setParameter("feedId", feedId)
                .setParameter("authorId", authorId).setParameter("content", content)
                .setParameter("createdAt", createdAt).getResultList();
        return rows.stream().map(JpaCommentRepository::snapshot).findFirst()
                .orElseThrow(() -> new IllegalStateException("댓글 저장 결과를 확인할 수 없습니다."));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean delete(UUID commentId, UUID feedId, Instant deletedAt) {
        Objects.requireNonNull(commentId, "commentId는 필수입니다.");
        Objects.requireNonNull(feedId, "feedId는 필수입니다.");
        Objects.requireNonNull(deletedAt, "deletedAt은 필수입니다.");
        return entityManager.createNativeQuery(DELETE_QUERY)
                .setParameter("commentId", commentId).setParameter("feedId", feedId)
                .setParameter("deletedAt", deletedAt).executeUpdate() == 1;
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<CommentSnapshot> findAfter(UUID feedId, Optional<CommentCursor> cursor, int limit) {
        Objects.requireNonNull(feedId, "feedId는 필수입니다.");
        Objects.requireNonNull(cursor, "cursor는 필수입니다.");
        if (limit < 1 || limit > 51 || cursor.filter(value -> !feedId.equals(value.feedId())).isPresent()) {
            throw new IllegalArgumentException("댓글 조회 조건이 올바르지 않습니다.");
        }
        Query query = entityManager.createNativeQuery(
                LIST_QUERY + (cursor.isPresent() ? AFTER_PREDICATE : "") + ORDER_QUERY
        ).setParameter("feedId", feedId).setMaxResults(limit);
        cursor.ifPresent(value -> query.setParameter("afterCreatedAt", value.createdAt())
                .setParameter("afterId", value.id()));
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(JpaCommentRepository::snapshot).toList();
    }

    private long count(String query, String parameter, UUID id) {
        return ((Number) entityManager.createNativeQuery(query).setParameter(parameter, id)
                .getSingleResult()).longValue();
    }

    private static CommentSnapshot snapshot(Object[] row) {
        return new CommentSnapshot((UUID) row[0], (UUID) row[1], (UUID) row[2], (String) row[3], instant(row[4]));
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime dateTime) {
            return dateTime.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new IllegalStateException("댓글 생성 시각의 DB 형식을 확인할 수 없습니다.");
    }
}
