package com.harudle.push.service.port;

import java.time.Instant;
import java.util.UUID;

/** 새 피드 푸시를 예약하거나 취소한다. 입력값과 각 필드는 null을 허용하지 않는다. */
public interface FeedPushOutbox {

    /**
     * 작성자를 포함한 활성 회원의 해제되지 않은 등록 기기마다 발송 작업을 저장하고, 새 작업 수를 반환한다.
     * 피드 저장과 같은 트랜잭션에서 처리한다. 예약 저장이 실패하면 피드 저장도 취소한다.
     * eventId는 게시 한 번에 하나다. 같은 이벤트와 기기 등록은 중복 예약하지 않으며, 새 작업이 없으면 0을 반환한다.
     * 실제 FCM 전송은 커밋 후에 한다. 전송 재시도는 새로 예약하지 않고 기존 작업을 사용한다.
     */
    int enqueuePublished(FeedPublished event);

    /**
     * 피드 삭제와 같은 트랜잭션에서 대기/처리 중인 작업(PENDING/PROCESSING)을 취소(CANCELLED)한다.
     * 취소할 때 잠금 정보와 발송 시각(sent_at)을 비운다. 이미 외부로 전송 중인 메시지는 회수할 수 없다.
     * 이번에 취소한 작업 수를 반환한다. 대상이 없거나 이미 취소됐다면 0이다.
     */
    int cancelUnsentByFeed(UUID feedId);

    record FeedPublished(UUID eventId, UUID feedId, Instant publishedAt) {}
}
