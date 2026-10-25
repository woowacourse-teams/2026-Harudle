package com.harudle.feed.infrastructure;

import com.harudle.feed.service.port.FeedLikeReader;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaFeedLikeReader implements FeedLikeReader {

    private static final String FIND_LIKED_FEEDS_QUERY = """
            SELECT l.feed_id
            FROM feed_likes l
            JOIN feeds f ON f.id = l.feed_id
            JOIN diaries d ON d.id = f.diary_id
            WHERE l.user_id = :viewerId
              AND l.feed_id IN (:feedIds)
              AND f.deleted_at IS NULL
              AND d.deleted_at IS NULL
            """;

    private final EntityManager entityManager;

    JpaFeedLikeReader(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Set<UUID> findLikedFeedIds(UUID viewerId, Set<UUID> feedIds) {
        Objects.requireNonNull(viewerId, "viewerId는 필수입니다.");
        Objects.requireNonNull(feedIds, "feedIds는 필수입니다.");
        Set<UUID> requestedIds = Set.copyOf(feedIds);
        if (requestedIds.isEmpty()) {
            return Set.of();
        }
        List<UUID> likedIds = entityManager.createNativeQuery(FIND_LIKED_FEEDS_QUERY)
                .setParameter("viewerId", viewerId)
                .setParameter("feedIds", requestedIds)
                .getResultList();
        return Set.copyOf(likedIds);
    }
}
