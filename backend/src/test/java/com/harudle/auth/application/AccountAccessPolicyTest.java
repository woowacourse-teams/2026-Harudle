package com.harudle.auth.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.harudle.auth.application.port.AccountRestrictionReader;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class AccountAccessPolicyTest {
    private final AccountRestrictionReader restrictions = mock(AccountRestrictionReader.class);
    private final AccountAccessPolicy policy = new AccountAccessPolicy(restrictions);
    private final UUID userId = UUID.randomUUID();

    @Test
    @DisplayName("관리자가 해제하기 전까지 정지된 회원을 거절하고 해제 뒤에는 같은 회원을 허용한다")
    void rejectsSuspendedAccountUntilReleased() {
        when(restrictions.isSuspended(userId)).thenReturn(true, false);
        assertThatThrownBy(() -> policy.requireAllowed(userId)).isInstanceOf(AccountSuspendedException.class);
        assertThatCode(() -> policy.requireAllowed(userId)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("제재 상태 조회 장애를 정상 회원이나 정지 상태로 바꾸지 않고 원래 장애로 전파한다")
    void propagatesUnavailableRestrictionRead() {
        var failure = new DataAccessResourceFailureException("테스트 조회 실패");
        when(restrictions.isSuspended(userId)).thenThrow(failure);
        assertThatThrownBy(() -> policy.requireAllowed(userId)).isSameAs(failure);
    }
}
