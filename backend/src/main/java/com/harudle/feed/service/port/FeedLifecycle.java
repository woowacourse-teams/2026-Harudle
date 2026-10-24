package com.harudle.feed.service.port;

import java.time.Instant;
import java.util.UUID;

/** 개인 일기를 삭제할 때 연결된 피드도 정리한다. 입력값은 null을 허용하지 않는다. */
public interface FeedLifecycle {

    /**
     * 일기를 잠그고 본인 소유인지 확인한 뒤, 일기 삭제와 같은 트랜잭션에서 호출한다.
     * 연결 피드를 잠가 삭제 시각을 기록하고, 아직 보내지 않은 푸시 작업을 취소한다.
     * 연결 피드가 없거나 이미 삭제됐다면 아무것도 하지 않는다. 일기와 원본 이미지는 여기서 삭제하지 않는다.
     * 이후 조회에서는 해당 피드의 댓글과 좋아요를 숨기고, 알림 목록과 미읽음 수에서도 제외해야 한다.
     */
    void deleteByDiary(UUID diaryId, Instant deletedAt);
}
