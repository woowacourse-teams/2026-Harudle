package com.harudle.auth.application;

import com.harudle.auth.application.port.AccountRestrictionReader;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AccountAccessPolicy {
    private final AccountRestrictionReader restrictions;

    public AccountAccessPolicy(AccountRestrictionReader restrictions) {
        this.restrictions = restrictions;
    }

    /** 일반 회원 요청을 검사하며 제재 변경 트랜잭션의 사용자 행 잠금을 기다리지 않는다. */
    public void requireAllowedForRequest(UUID userId) {
        Objects.requireNonNull(userId, "회원 ID는 필수입니다.");
        if (restrictions.isSuspendedForRequest(userId)) {
            throw new AccountSuspendedException();
        }
    }

    /** 토큰 발급·회전은 사용자 공유 잠금을 먼저 획득해 제재와 직렬화한다. */
    public void requireAllowed(UUID userId) {
        Objects.requireNonNull(userId, "회원 ID는 필수입니다.");
        if (restrictions.isSuspended(userId)) {
            throw new AccountSuspendedException();
        }
    }
}
