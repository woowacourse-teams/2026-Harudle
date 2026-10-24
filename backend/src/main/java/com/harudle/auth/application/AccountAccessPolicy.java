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

    public void requireAllowed(UUID userId) {
        Objects.requireNonNull(userId, "회원 ID는 필수입니다.");
        if (restrictions.isSuspended(userId)) {
            throw new AccountSuspendedException();
        }
    }
}
