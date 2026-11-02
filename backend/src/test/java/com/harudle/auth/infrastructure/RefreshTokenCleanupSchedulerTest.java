package com.harudle.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.harudle.auth.application.RefreshTokenCleanupService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefreshTokenCleanupSchedulerTest {

    private static final Instant CUTOFF = Instant.parse("2026-08-26T10:00:00Z");

    @Mock
    private RefreshTokenCleanupService cleanupService;

    private RefreshTokenCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new RefreshTokenCleanupScheduler(cleanupService, Clock.fixed(CUTOFF, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("시계에서 얻은 기준 시각으로 정리 서비스를 한 번 호출한다")
    void cleansUpUsingCurrentClockTime() {
        when(cleanupService.deleteExpiredTokens(CUTOFF)).thenReturn(2);

        scheduler.cleanupExpiredTokens();

        verify(cleanupService).deleteExpiredTokens(CUTOFF);
        verifyNoMoreInteractions(cleanupService);
    }

    @Test
    @DisplayName("삭제가 실패하면 예외를 다시 던지고 즉시 재시도하지 않는다")
    void propagatesCleanupFailure() {
        RuntimeException failure = new IllegalStateException("정리 실패");
        when(cleanupService.deleteExpiredTokens(CUTOFF)).thenThrow(failure);

        assertThatThrownBy(scheduler::cleanupExpiredTokens).isSameAs(failure);

        verify(cleanupService).deleteExpiredTokens(CUTOFF);
        verifyNoMoreInteractions(cleanupService);
    }
}
