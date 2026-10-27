package com.harudle.notification.service.port;

import java.time.Instant;
import java.util.UUID;

/** 댓글과 좋아요 알림을 앱의 알림 목록에 기록한다. 입력값과 각 필드는 null을 허용하지 않는다. */
public interface InAppNotificationRecorder {

    /**
     * 댓글/좋아요 저장과 같은 트랜잭션에서 알림을 기록한다. FCM 전송은 하지 않는다.
     * sourceActionId에는 저장된 댓글/좋아요 ID를, recipientUserId에는 잠근 피드의 작성자 ID를 넣는다.
     * 새 알림은 CREATED, 같은 수신자/종류/행동 ID의 알림은 DUPLICATE, 자기 피드의 반응은 SELF_SKIPPED다.
     * 중복과 자기 알림은 오류로 처리하지 않는다. 반복 좋아요 PUT과 좋아요 취소에서는 호출하지 않는다.
     */
    Result record(Interaction event);

    record Interaction(
            UUID sourceActionId,
            Type type,
            UUID feedId,
            UUID actorUserId,
            UUID recipientUserId,
            Instant occurredAt
    ) {}

    enum Type { LIKE, COMMENT }

    enum Result { CREATED, DUPLICATE, SELF_SKIPPED }
}
