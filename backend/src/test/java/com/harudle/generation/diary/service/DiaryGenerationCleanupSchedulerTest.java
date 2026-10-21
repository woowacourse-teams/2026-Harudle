package com.harudle.generation.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.generation.config.GenerationLifecycleProperties;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class DiaryGenerationCleanupSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-08-11T03:00:00Z");
    private static final Duration PROCESSING_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration PENDING_TIMEOUT = Duration.ofSeconds(120);

    @Mock
    private DiaryGenerationRepository diaryGenerationRepository;

    @Mock
    private GenerationLifecycleProperties generationLifecycleProperties;

    @Mock
    private DiaryGenerationCompletionService completionService;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private DiaryGenerationCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new DiaryGenerationCleanupScheduler(
                diaryGenerationRepository,
                completionService,
                generationLifecycleProperties,
                clock
        );
    }

    @Test
    @DisplayName("처리 제한 시간을 지난 그림일기 생성 작업을 주기적으로 실패 처리한다")
    void expireStaleProcessingGenerations() {
        UUID generationId = UUID.randomUUID();
        when(generationLifecycleProperties.processingTimeout()).thenReturn(PROCESSING_TIMEOUT);
        when(diaryGenerationRepository.findStaleProcessingIds(
                org.mockito.ArgumentMatchers.eq(GenerationStatus.PROCESSING),
                org.mockito.ArgumentMatchers.eq(NOW.minus(PROCESSING_TIMEOUT)),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of(generationId));
        when(completionService.interruptIfStale(generationId, NOW, PROCESSING_TIMEOUT))
                .thenReturn(true);

        scheduler.expireStaleProcessingGenerations();

        verify(completionService).interruptIfStale(generationId, NOW, PROCESSING_TIMEOUT);
    }

    @Test
    @DisplayName("대기 기한이 지난 ID를 최대 100개 조회하고 상태를 다시 확인해 만료한다")
    void expireStalePendingGenerations(CapturedOutput output) {
        UUID expiredId = UUID.randomUUID();
        UUID claimedId = UUID.randomUUID();
        UUID anotherExpiredId = UUID.randomUUID();
        when(generationLifecycleProperties.pendingTimeout()).thenReturn(PENDING_TIMEOUT);
        when(diaryGenerationRepository.findStalePendingIds(NOW.minus(PENDING_TIMEOUT), 100))
                .thenReturn(List.of(expiredId, claimedId, anotherExpiredId));
        when(completionService.expirePendingIfStale(expiredId, NOW, PENDING_TIMEOUT)).thenReturn(true);
        when(completionService.expirePendingIfStale(claimedId, NOW, PENDING_TIMEOUT)).thenReturn(false);
        when(completionService.expirePendingIfStale(anotherExpiredId, NOW, PENDING_TIMEOUT)).thenReturn(true);

        scheduler.expireStalePendingGenerations();

        verify(completionService).expirePendingIfStale(expiredId, NOW, PENDING_TIMEOUT);
        verify(completionService).expirePendingIfStale(claimedId, NOW, PENDING_TIMEOUT);
        verify(completionService).expirePendingIfStale(anotherExpiredId, NOW, PENDING_TIMEOUT);
        assertThat(output).contains("event=generation_pending_cleanup_run candidateCount=3 expiredCount=2");
    }

    @Test
    @DisplayName("만료할 대기 작업이 없으면 상태 변경과 정리 완료 로그를 남기지 않는다")
    void skipEmptyPendingCleanup(CapturedOutput output) {
        when(generationLifecycleProperties.pendingTimeout()).thenReturn(PENDING_TIMEOUT);
        when(diaryGenerationRepository.findStalePendingIds(NOW.minus(PENDING_TIMEOUT), 100)).thenReturn(List.of());

        scheduler.expireStalePendingGenerations();

        verifyNoInteractions(completionService);
        assertThat(output).doesNotContain("event=generation_pending_cleanup_run");
    }

    @Test
    @DisplayName("정리 조회가 실패하면 오류 종류만 남기고 다음 실행에서 다시 조회한다")
    void retryCleanupOnNextRunWithoutLoggingPrivateMessage(CapturedOutput output) {
        when(generationLifecycleProperties.pendingTimeout()).thenReturn(PENDING_TIMEOUT);
        when(diaryGenerationRepository.findStalePendingIds(NOW.minus(PENDING_TIMEOUT), 100))
                .thenThrow(new IllegalStateException("private-diary-input"))
                .thenReturn(List.of());

        assertThatThrownBy(scheduler::expireStalePendingGenerations).isInstanceOf(IllegalStateException.class);
        scheduler.expireStalePendingGenerations();

        verify(diaryGenerationRepository, times(2)).findStalePendingIds(NOW.minus(PENDING_TIMEOUT), 100);
        verifyNoInteractions(completionService);
        assertThat(output).contains("event=generation_pending_cleanup_failed")
                .contains("exceptionType=IllegalStateException")
                .doesNotContain("private-diary-input");
    }

    @Test
    @DisplayName("설정한 간격으로 대기 만료 확인을 반복 실행한다")
    void schedulePendingCleanupUsingConfiguredInterval() {
        UUID generationId = UUID.randomUUID();
        CountDownLatch twoRuns = new CountDownLatch(2);
        when(generationLifecycleProperties.pendingTimeout()).thenReturn(PENDING_TIMEOUT);
        when(diaryGenerationRepository.findStalePendingIds(NOW.minus(PENDING_TIMEOUT), 100))
                .thenReturn(List.of(generationId));
        when(completionService.expirePendingIfStale(generationId, NOW, PENDING_TIMEOUT)).thenAnswer(invocation -> {
            twoRuns.countDown();
            return true;
        });

        new ApplicationContextRunner()
                .withUserConfiguration(SchedulingConfiguration.class)
                .withPropertyValues(
                        "harudle.generation.lifecycle.pending-cleanup-interval=20ms",
                        "harudle.generation.lifecycle.cleanup-interval=1h"
                )
                .withBean(DiaryGenerationCleanupScheduler.class, () -> scheduler)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(twoRuns.await(5, TimeUnit.SECONDS)).isTrue();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingConfiguration {
    }
}
