package com.harudle.push.infrastructure;

import com.harudle.push.service.port.FeedPushOutbox;
import com.harudle.push.service.port.FeedPushPublicationGuard;
import jakarta.persistence.EntityManager;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaFeedPushOutbox implements FeedPushOutbox {
    private static final String AUTHOR = """
            SELECT d.user_id FROM feeds f JOIN diaries d ON d.id = f.diary_id
            WHERE f.id = :feedId AND f.deleted_at IS NULL
            """;
    private static final String ENQUEUE = """
            INSERT INTO notification_outbox
                (id, event_id, feed_id, recipient_user_id, push_registration_id, available_at)
            SELECT gen_random_uuid(), :eventId, f.id, r.user_id, r.id, :publishedAt
            FROM feeds f
            JOIN diaries d ON d.id = f.diary_id
            JOIN push_registrations r ON r.user_id <> d.user_id AND r.revoked_at IS NULL
            JOIN users u ON u.id = r.user_id AND u.deleted_at IS NULL
            JOIN auth_sessions s ON s.id = r.session_id AND s.user_id = r.user_id AND s.revoked_at IS NULL
            WHERE f.id = :feedId AND f.deleted_at IS NULL
              AND NOT EXISTS (
                  SELECT 1 FROM user_blocks b
                  WHERE (b.blocker_id = r.user_id AND b.blocked_id = d.user_id)
                     OR (b.blocker_id = d.user_id AND b.blocked_id = r.user_id)
              )
              AND NOT EXISTS (SELECT 1 FROM feeds hidden_feed
                  JOIN moderation_states moderation ON moderation.target_type = 'FEED'
                    AND moderation.target_id = hidden_feed.id AND moderation.restricted
                  WHERE hidden_feed.diary_id = f.diary_id)
              AND EXISTS (
                  SELECT 1 FROM refresh_tokens rt
                  WHERE rt.session_id = s.id AND rt.user_id = s.user_id
                    AND rt.revoked_at IS NULL AND rt.expires_at > CURRENT_TIMESTAMP
              )
            ON CONFLICT (event_id, push_registration_id) DO NOTHING
            """;

    private static final String CANCEL = """
            UPDATE notification_outbox
            SET status = 'CANCELLED', lock_token = NULL, locked_until = NULL,
                sent_at = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE feed_id = :feedId AND status IN ('PENDING', 'PROCESSING')
            """;

    private static final String CANCEL_BETWEEN_USERS = """
            UPDATE notification_outbox o
            SET status = 'CANCELLED', lock_token = NULL, locked_until = NULL,
                sent_at = NULL, updated_at = CURRENT_TIMESTAMP
            FROM feeds f JOIN diaries d ON d.id = f.diary_id
            WHERE o.feed_id = f.id AND o.status IN ('PENDING', 'PROCESSING')
              AND ((o.recipient_user_id = :first AND d.user_id = :second)
                OR (o.recipient_user_id = :second AND d.user_id = :first))
            """;

    private final EntityManager entityManager;
    private final ApplicationEventPublisher events;
    private final FeedPushPublicationGuard publicationGuard;

    JpaFeedPushOutbox(EntityManager entityManager, ApplicationEventPublisher events,
            FeedPushPublicationGuard publicationGuard) {
        this.entityManager = entityManager;
        this.events = events;
        this.publicationGuard = publicationGuard;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int enqueuePublished(FeedPublished event) {
        Objects.requireNonNull(event, "event는 필수입니다.");
        Objects.requireNonNull(event.eventId(), "eventId는 필수입니다.");
        Objects.requireNonNull(event.feedId(), "feedId는 필수입니다.");
        Objects.requireNonNull(event.publishedAt(), "publishedAt은 필수입니다.");
        var authors = entityManager.createNativeQuery(AUTHOR, UUID.class)
                .setParameter("feedId", event.feedId()).getResultList();
        if (authors.isEmpty()) {
            return 0;
        }
        publicationGuard.lockForPublication((UUID) authors.getFirst());
        // 잠금을 기다리는 동안 차단이 커밋될 수 있으므로 반드시 별도 RC 문장에서 다시 검사한다.
        int created = entityManager.createNativeQuery(ENQUEUE)
                .setParameter("eventId", event.eventId())
                .setParameter("feedId", event.feedId())
                .setParameter("publishedAt", event.publishedAt())
                .executeUpdate();
        if (created > 0) {
            events.publishEvent(new JobsQueued());
        }
        return created;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelUnsentByFeed(UUID feedId) {
        Objects.requireNonNull(feedId, "feedId는 필수입니다.");
        return entityManager.createNativeQuery(CANCEL)
                .setParameter("feedId", feedId)
                .executeUpdate();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelUnsentBetweenUsers(UUID firstUserId, UUID secondUserId) {
        Objects.requireNonNull(firstUserId, "첫 번째 회원 ID는 필수입니다.");
        Objects.requireNonNull(secondUserId, "두 번째 회원 ID는 필수입니다.");
        return entityManager.createNativeQuery(CANCEL_BETWEEN_USERS)
                .setParameter("first", firstUserId)
                .setParameter("second", secondUserId)
                .executeUpdate();
    }

    record JobsQueued() { }
}
