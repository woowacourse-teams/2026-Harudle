package com.harudle.push.service.port;

import java.util.UUID;

/** 푸시 예약과 차단 취소를 같은 트랜잭션 경계에서 조정한다. 호출자는 READ COMMITTED를 사용한다. */
public interface FeedPushPublicationGuard {
    /** 같은 작성자의 여러 게시가 공유할 수 있는 잠금을 트랜잭션 종료까지 유지한다. */
    void lockForPublication(UUID authorId);

    /** 두 작성자의 진행 중인 예약이 모두 커밋된 뒤 차단 관계와 미발송 취소를 저장한다. */
    void lockForBlock(UUID firstAuthorId, UUID secondAuthorId);
}
