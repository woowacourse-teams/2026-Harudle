package com.harudle.feed.infrastructure;

import com.harudle.feed.repository.FeedLikeRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaFeedLikeRepository implements FeedLikeRepository {

    private static final String ACTIVE_MEMBER_QUERY = """
            SELECT count(*)
            FROM users u
            WHERE u.id = :userId
              AND u.deleted_at IS NULL
              AND EXISTS (SELECT 1 FROM oauth_accounts a WHERE a.user_id = u.id)
            """;
    private static final String ADD_QUERY = """
            INSERT INTO feed_likes (id, feed_id, user_id, created_at)
            VALUES (:likeId, :feedId, :userId, :createdAt)
            ON CONFLICT (feed_id, user_id) DO NOTHING
            """;
    private static final String REMOVE_QUERY = """
            DELETE FROM feed_likes
            WHERE feed_id = :feedId AND user_id = :userId
            """;

    private final EntityManager entityManager;

    JpaFeedLikeRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActiveMember(UUID userId) {
        Objects.requireNonNull(userId, "userId는 필수입니다.");
        Number count = (Number) entityManager.createNativeQuery(ACTIVE_MEMBER_QUERY)
                .setParameter("userId", userId)
                .getSingleResult();
        return count.longValue() > 0;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean add(UUID likeId, UUID feedId, UUID userId, Instant createdAt) {
        Objects.requireNonNull(likeId, "likeId는 필수입니다.");
        Objects.requireNonNull(feedId, "feedId는 필수입니다.");
        Objects.requireNonNull(userId, "userId는 필수입니다.");
        Objects.requireNonNull(createdAt, "createdAt은 필수입니다.");
        return entityManager.createNativeQuery(ADD_QUERY)
                .setParameter("likeId", likeId)
                .setParameter("feedId", feedId)
                .setParameter("userId", userId)
                .setParameter("createdAt", createdAt)
                .executeUpdate() == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean remove(UUID feedId, UUID userId) {
        Objects.requireNonNull(feedId, "feedId는 필수입니다.");
        Objects.requireNonNull(userId, "userId는 필수입니다.");
        return entityManager.createNativeQuery(REMOVE_QUERY)
                .setParameter("feedId", feedId)
                .setParameter("userId", userId)
                .executeUpdate() == 1;
    }
}
