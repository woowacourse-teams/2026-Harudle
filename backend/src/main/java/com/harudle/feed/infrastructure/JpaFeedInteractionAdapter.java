package com.harudle.feed.infrastructure;

import com.harudle.feed.service.exception.FeedNotFoundException;
import com.harudle.feed.service.port.FeedInteractionPort;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 피드 CRUD 모델과 분리한 반응 전용 어댑터. 피드 행만 잠그며 원본 일기는 잠그지 않는다. */
@Repository
class JpaFeedInteractionAdapter implements FeedInteractionPort {

    private static final String LOCK_ACTIVE_QUERY = """
            SELECT f.id, d.user_id, f.like_count, f.comment_count
            FROM feeds f
            JOIN diaries d ON d.id = f.diary_id
            WHERE f.id = :feedId
              AND f.deleted_at IS NULL
              AND d.deleted_at IS NULL
            FOR UPDATE OF f
            """;
    private static final String ADJUST_LIKES_QUERY = """
            UPDATE feeds
            SET like_count = like_count + :delta
            WHERE id = :feedId
              AND deleted_at IS NULL
              AND like_count + :delta >= 0
            RETURNING like_count, comment_count
            """;
    private static final String ADJUST_COMMENTS_QUERY = """
            UPDATE feeds
            SET comment_count = comment_count + :delta
            WHERE id = :feedId
              AND deleted_at IS NULL
              AND comment_count + :delta >= 0
            RETURNING like_count, comment_count
            """;

    private final EntityManager entityManager;

    JpaFeedInteractionAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    @SuppressWarnings("unchecked")
    public Target lockActive(UUID feedId) {
        Objects.requireNonNull(feedId, "feedId는 필수입니다.");
        List<Object[]> rows = entityManager.createNativeQuery(LOCK_ACTIVE_QUERY)
                .setParameter("feedId", feedId)
                .getResultList();
        return rows.stream()
                .map(row -> new Target((UUID) row[0], (UUID) row[1], counts(row, 2)))
                .findFirst()
                .orElseThrow(FeedNotFoundException::new);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    @SuppressWarnings("unchecked")
    public Counts adjustCounter(UUID feedId, CounterKind kind, CounterChange change) {
        Objects.requireNonNull(feedId, "feedId는 필수입니다.");
        Objects.requireNonNull(kind, "kind는 필수입니다.");
        Objects.requireNonNull(change, "change는 필수입니다.");
        String query = switch (kind) {
            case LIKE -> ADJUST_LIKES_QUERY;
            case COMMENT -> ADJUST_COMMENTS_QUERY;
        };
        int delta = switch (change) {
            case ADDED -> 1;
            case REMOVED -> -1;
        };
        List<Object[]> rows = entityManager.createNativeQuery(query)
                .setParameter("feedId", feedId)
                .setParameter("delta", delta)
                .getResultList();
        return rows.stream()
                .map(row -> counts(row, 0))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("잠근 피드의 반응 수를 변경할 수 없습니다."));
    }

    private static Counts counts(Object[] row, int offset) {
        return new Counts(((Number) row[offset]).intValue(), ((Number) row[offset + 1]).intValue());
    }
}
