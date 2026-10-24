package com.harudle.auth.application.port;

import java.util.UUID;

@FunctionalInterface
public interface AccountRestrictionReader {
    /**
     * 회원 공유 잠금을 먼저 획득한 뒤 제재 상태를 읽는다.
     * 호출자 트랜잭션이 있으면 잠금을 종료까지 유지하므로 토큰 발급은 이 검사를 세션·토큰 잠금보다 먼저 수행한다.
     */
    boolean isSuspended(UUID userId);
}
