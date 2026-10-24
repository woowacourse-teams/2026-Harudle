package com.harudle.notification.infrastructure;

import com.harudle.notification.service.port.InAppNotificationRecorder;
import jakarta.persistence.EntityManager;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 반응 저장 트랜잭션에서만 기록한다. 외부 푸시나 비동기 전송을 수행하지 않는다. */
@Repository
class JpaInAppNotificationRecorder implements InAppNotificationRecorder {

    private static final String RECORD_QUERY = """
            INSERT INTO notifications (
                id, recipient_user_id, actor_user_id, feed_id, type, source_action_id, created_at
            )
            VALUES (:id, :recipientUserId, :actorUserId, :feedId, :type, :sourceActionId, :occurredAt)
            ON CONFLICT (recipient_user_id, type, source_action_id) DO NOTHING
            """;

    private final EntityManager entityManager;

    JpaInAppNotificationRecorder(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Result record(Interaction event) {
        validate(event);
        if (event.actorUserId().equals(event.recipientUserId())) {
            return Result.SELF_SKIPPED;
        }
        int added = entityManager.createNativeQuery(RECORD_QUERY)
                .setParameter("id", UUID.randomUUID())
                .setParameter("recipientUserId", event.recipientUserId())
                .setParameter("actorUserId", event.actorUserId())
                .setParameter("feedId", event.feedId())
                .setParameter("type", event.type().name())
                .setParameter("sourceActionId", event.sourceActionId())
                .setParameter("occurredAt", event.occurredAt())
                .executeUpdate();
        return added == 1 ? Result.CREATED : Result.DUPLICATE;
    }

    private static void validate(Interaction event) {
        Objects.requireNonNull(event, "event는 필수입니다.");
        Objects.requireNonNull(event.sourceActionId(), "sourceActionId는 필수입니다.");
        Objects.requireNonNull(event.type(), "type은 필수입니다.");
        Objects.requireNonNull(event.feedId(), "feedId는 필수입니다.");
        Objects.requireNonNull(event.actorUserId(), "actorUserId는 필수입니다.");
        Objects.requireNonNull(event.recipientUserId(), "recipientUserId는 필수입니다.");
        Objects.requireNonNull(event.occurredAt(), "occurredAt은 필수입니다.");
    }
}
