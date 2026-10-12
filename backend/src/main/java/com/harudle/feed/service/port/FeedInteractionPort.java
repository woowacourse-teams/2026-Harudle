package com.harudle.feed.service.port;

import java.util.UUID;

/** 피드를 확인하고 좋아요 수와 댓글 수를 변경한다. 입력값은 null을 허용하지 않는다. */
public interface FeedInteractionPort {

    /**
     * 피드 작성자 ID와 현재 좋아요 수, 댓글 수를 반환한다. 피드가 없거나 피드/원본 일기가 삭제됐으면 예외를 던진다.
     * 좋아요/댓글 변경과 같은 트랜잭션에서 피드를 잠그고, 끝날 때까지 유지한다.
     * 원본 일기는 잠그지 않고 삭제 여부만 확인한다.
     */
    Target lockActive(UUID feedId);

    /**
     * lockActive와 같은 트랜잭션에서 잠근 피드의 좋아요 수 또는 댓글 수를 1 늘리거나 줄이고, 변경 후 개수를 반환한다.
     * 실제 추가/삭제가 있을 때만 호출한다. 같은 요청을 반복해 데이터가 그대로라면 호출하지 않는다.
     * 반응 저장, 개수 변경, 인앱 알림은 같은 트랜잭션으로 묶어 하나라도 실패하면 모두 취소한다.
     * 개수는 0보다 작아질 수 없다.
     */
    Counts adjustCounter(UUID feedId, CounterKind kind, CounterChange change);

    record Target(UUID feedId, UUID authorId, Counts counts) {}

    record Counts(int likeCount, int commentCount) {}

    enum CounterKind { LIKE, COMMENT }

    enum CounterChange { ADDED, REMOVED }
}
