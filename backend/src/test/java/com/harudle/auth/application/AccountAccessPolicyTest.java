package com.harudle.auth.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        verify(restrictions, times(2)).isSuspended(userId);
        verify(restrictions, never()).isSuspendedForRequest(userId);
    }

    @Test
    @DisplayName("제재 상태 조회 장애를 정상 회원이나 정지 상태로 바꾸지 않고 원래 장애로 전파한다")
    void propagatesUnavailableRestrictionRead() {
        var failure = new DataAccessResourceFailureException("테스트 조회 실패");
        when(restrictions.isSuspended(userId)).thenThrow(failure);
        assertThatThrownBy(() -> policy.requireAllowed(userId)).isSameAs(failure);
        verify(restrictions).isSuspended(userId);
        verify(restrictions, never()).isSuspendedForRequest(userId);
    }

    @Test
    @DisplayName("일반 요청은 잠금 없는 조회로 정지된 회원을 거절하고 해제 뒤에는 허용한다")
    void checksRequestAccessWithoutTokenIssuanceLock() {
        when(restrictions.isSuspendedForRequest(userId)).thenReturn(true, false);

        assertThatThrownBy(() -> policy.requireAllowedForRequest(userId))
                .isInstanceOf(AccountSuspendedException.class);
        assertThatCode(() -> policy.requireAllowedForRequest(userId)).doesNotThrowAnyException();
        verify(restrictions, times(2)).isSuspendedForRequest(userId);
        verify(restrictions, never()).isSuspended(userId);
    }

    @Test
    @DisplayName("일반 요청의 제재 조회 장애도 토큰 발급용 잠금 조회로 재시도하지 않고 전파한다")
    void propagatesRequestRestrictionReadFailureWithoutLockingFallback() {
        var failure = new DataAccessResourceFailureException("테스트 요청 조회 실패");
        when(restrictions.isSuspendedForRequest(userId)).thenThrow(failure);

        assertThatThrownBy(() -> policy.requireAllowedForRequest(userId)).isSameAs(failure);
        verify(restrictions).isSuspendedForRequest(userId);
        verify(restrictions, never()).isSuspended(userId);
    }
}
