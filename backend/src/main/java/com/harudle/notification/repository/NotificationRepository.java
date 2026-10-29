package com.harudle.notification.repository;

import com.harudle.notification.query.NotificationCursor;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository {
    boolean isActiveMember(UUID userId);

    /**
     * 공개 중인 부모 피드·일기의 본인 알림만 조회한다.
     * LIKE는 source_action_id가 실제 좋아요의 id이며 feed_id·actor_id도 일치할 때만 노출한다.
     * 취소된 좋아요의 DB 알림 이력은 남기되 숨기고, 재좋아요의 새 행동 ID는 새 알림으로 노출한다.
     * COMMENT는 원인 댓글이 삭제되어도 알림을 유지한다.
     * 수신자와 행동자 또는 부모 피드 작성자 사이에 어느 방향의 차단이 있어도 숨긴다.
     */
    List<NotificationSnapshot> findAfter(UUID recipientId, Optional<NotificationCursor> cursor, int limit);

    /** findAfter와 같은 부모·차단·원인 좋아요 가시성 조건으로 전체 미읽음 수를 센다. */
    long countUnread(UUID recipientId);

    /**
     * findAfter와 같은 부모·차단·원인 좋아요 가시성 조건으로 알림을 찾는다.
     * 취소한 LIKE는 없어짐으로 처리하되 DB 이력을 삭제하지 않는다. 소유자 검증은 서비스에서 수행한다.
     */
    Optional<NotificationReadTarget> findVisibleById(UUID notificationId);

    /**
     * 부모·차단·원인 좋아요 조건을 만족해 여전히 보이는 본인 알림에 최초 read_at만 기록한다.
     * 반복 요청도 보이는 본인 알림이면 true이며, 취소된 LIKE를 포함한 비노출 알림은 false다.
     */
    boolean markRead(UUID notificationId, UUID recipientId, Instant readAt);
}
