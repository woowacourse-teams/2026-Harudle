package com.harudle.generation.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.diary.domain.Diary;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.domain.GenerationErrorCode;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.domain.GenerationTokenUsage;
import com.harudle.generation.diary.domain.StoryPanel;
import com.harudle.generation.diary.domain.Storyboard;
import com.harudle.generation.diary.domain.StoryboardProviderInfo;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.event.DiaryGenerationPendingExpiredEvent;
import com.harudle.generation.diary.service.exception.DiaryGenerationFailedException;
import com.harudle.generation.usage.domain.GenerationUsage;
import com.harudle.generation.usage.service.GenerationUsageService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class DiaryGenerationCompletionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");
    private static final UUID USER_ID = UUID.randomUUID();
    private static final LocalDate USAGE_DATE = LocalDate.of(2026, 8, 6);

    @Mock
    private DiaryGenerationRepository diaryGenerationRepository;

    @Mock
    private DiaryRepository diaryRepository;

    @Mock
    private GenerationUsageService generationUsageService;

    @Mock
    private ApplicationEventPublisher events;

    private DiaryGenerationCompletionService completionService;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        meterRegistry = new SimpleMeterRegistry();
        completionService = new DiaryGenerationCompletionService(
                diaryGenerationRepository,
                diaryRepository,
                generationUsageService,
                clock,
                new GenerationLifecycleMetrics(meterRegistry),
                events
        );
    }

    @Test
    @DisplayName("행 잠금으로 조회한 처리 중 생성을 성공 상태로 완료한다")
    void succeedProcessingGeneration() {
        DiaryGeneration generation = createGeneration();
        Storyboard storyboard = createStoryboard();
        GenerationTokenUsage tokenUsage = new GenerationTokenUsage(120, 350, 80, 550);
        when(diaryGenerationRepository.findByIdForUpdate(generation.getId()))
                .thenReturn(Optional.of(generation));

        DiaryGeneration result = completionService.succeed(
                generation.getId(),
                storyboard,
                "generated/comic.png",
                tokenUsage
        );

        assertThat(result.getStatus()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(result.getCompletedAt()).isEqualTo(NOW);
        assertThat(result.getTokenUsage()).isEqualTo(tokenUsage);
    }

    @Test
    @DisplayName("대기 작업을 성공으로 완료하거나 완료 지표를 기록하지 않는다")
    void rejectSucceedingPendingGeneration() {
        DiaryGeneration generation = DiaryGeneration.pending(
                UUID.randomUUID(), 1L, UUID.randomUUID(), "a".repeat(64), USAGE_DATE
        );
        when(diaryGenerationRepository.findByIdForUpdate(generation.getId()))
                .thenReturn(Optional.of(generation));

        assertThatThrownBy(() -> completionService.succeed(
                generation.getId(), createStoryboard(), "generated/comic.png", null
        )).isInstanceOf(IllegalStateException.class);

        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.PENDING);
        assertThat(generation.getCompletedAt()).isNull();
        assertNoFinalizations();
    }

    @Test
    @DisplayName("대기 작업의 실패 처리를 거부하고 일기와 사용량을 유지한다")
    void rejectFailingPendingGeneration() {
        DiaryGeneration generation = DiaryGeneration.pending(
                UUID.randomUUID(), 1L, UUID.randomUUID(), "a".repeat(64), USAGE_DATE
        );
        Diary diary = mock(Diary.class);
        when(diaryGenerationRepository.findByIdForUpdate(generation.getId()))
                .thenReturn(Optional.of(generation));
        when(diaryRepository.findByIdIncludingDeletedForUpdate(generation.getDiaryId()))
                .thenReturn(Optional.of(diary));

        assertThatThrownBy(() -> completionService.fail(generation.getId(), GenerationErrorCode.AI_PROVIDER_TIMEOUT))
                .isInstanceOf(IllegalStateException.class);

        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.PENDING);
        assertThat(generation.getCompletedAt()).isNull();
        verify(diary, never()).delete(any());
        verify(generationUsageService, never()).restoreUsage(any(), any());
        assertNoFinalizations();
    }

    @Test
    @DisplayName("성공 결과의 Provider와 모델을 토큰 및 완료 상태와 함께 저장한다")
    void persistWinningProviderWithCompletion() {
        DiaryGeneration generation = createGeneration();
        StoryboardProviderInfo info = new StoryboardProviderInfo("qwen", "qwen3.8-max");
        when(diaryGenerationRepository.findByIdForUpdate(generation.getId())).thenReturn(Optional.of(generation));

        DiaryGeneration result = completionService.succeed(generation.getId(), createStoryboard(),
                "generated/comic.png", new GenerationTokenUsage(100, 390, 110, 600), info);

        assertThat(result.getStoryboardProvider()).isEqualTo("qwen");
        assertThat(result.getStoryboardModel()).isEqualTo("qwen3.8-max");
        assertThat(result.getStatus()).isEqualTo(GenerationStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("늦게 도착한 성공 결과가 이미 확정된 Provider와 모델을 덮어쓰지 않는다")
    void retainPreviouslyCommittedProviderInfo() {
        DiaryGeneration generation = createGeneration();
        generation.succeed(createStoryboard(), "generated/winner.png", null,
                new StoryboardProviderInfo("gemini", "winner-model"), NOW.minusSeconds(1));
        when(diaryGenerationRepository.findByIdForUpdate(generation.getId())).thenReturn(Optional.of(generation));

        DiaryGeneration result = completionService.succeed(generation.getId(), createStoryboard(),
                "generated/loser.png", null, new StoryboardProviderInfo("qwen", "loser-model"));

        assertThat(result.getStoryboardProvider()).isEqualTo("gemini");
        assertThat(result.getStoryboardModel()).isEqualTo("winner-model");
        assertThat(result.getImageObjectKey()).isEqualTo("generated/winner.png");
        assertNoFinalizations();
    }

    @Test
    @DisplayName("이미지 단계 실패 시 성공한 스토리보드의 Provider와 모델을 최종 실패와 함께 저장한다")
    void persistStoryboardProviderWhenImageStageFails() {
        DiaryGeneration generation = createGeneration();
        Diary diary = mock(Diary.class);
        when(diaryGenerationRepository.findByIdForUpdate(generation.getId())).thenReturn(Optional.of(generation));
        when(diaryRepository.findByIdIncludingDeletedForUpdate(generation.getDiaryId())).thenReturn(Optional.of(diary));

        GenerationErrorCode error = completionService.fail(generation.getId(), GenerationErrorCode.IMAGE_STORAGE_ERROR,
                new StoryboardProviderInfo("qwen", "qwen3.8-max"));

        assertThat(error).isEqualTo(GenerationErrorCode.IMAGE_STORAGE_ERROR);
        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(generation.getStoryboardProvider()).isEqualTo("qwen");
        assertThat(generation.getStoryboardModel()).isEqualTo("qwen3.8-max");
        verify(diary).delete(NOW);
    }

    @Test
    @DisplayName("이미 실패한 생성을 늦게 도착한 성공 결과가 덮어쓸 수 없다")
    void succeedDoesNotOverwriteFailedGeneration() {
        DiaryGeneration generation = createGeneration();
        generation.fail(GenerationErrorCode.GENERATION_INTERRUPTED, NOW.minusSeconds(1));
        when(diaryGenerationRepository.findByIdForUpdate(generation.getId()))
                .thenReturn(Optional.of(generation));

        assertThatThrownBy(() -> completionService.succeed(
                generation.getId(),
                createStoryboard(),
                "generated/comic.png",
                null
        )).isInstanceOfSatisfying(
                DiaryGenerationFailedException.class,
                exception -> assertThat(exception.errorCode())
                        .isEqualTo(GenerationErrorCode.GENERATION_INTERRUPTED)
        );
        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.FAILED);
    }

    @Test
    @DisplayName("이미 성공한 생성을 늦게 도착한 성공 결과가 덮어쓰지 않는다")
    void succeedKeepsExistingSuccessfulGeneration() {
        DiaryGeneration generation = createGeneration();
        Instant firstCompletedAt = NOW.minusSeconds(1);
        generation.succeed(createStoryboard(), "generated/winner.png", firstCompletedAt);
        when(diaryGenerationRepository.findByIdForUpdate(generation.getId()))
                .thenReturn(Optional.of(generation));

        DiaryGeneration result = completionService.succeed(
                generation.getId(),
                createStoryboard(),
                "generated/loser.png",
                null
        );

        assertThat(result).isSameAs(generation);
        assertThat(result.getImageObjectKey()).isEqualTo("generated/winner.png");
        assertThat(result.getCompletedAt()).isEqualTo(firstCompletedAt);
        assertNoFinalizations();
    }

    @Test
    @DisplayName("처리 중 생성을 실패 상태로 바꾸며 일기를 함께 폐기한다")
    void failProcessingGenerationAndDiscardDiary() {
        DiaryGeneration generation = createGeneration(USAGE_DATE);
        Diary diary = mock(Diary.class);
        when(diary.getUserId()).thenReturn(USER_ID);
        when(diaryGenerationRepository.findByIdForUpdate(generation.getId()))
                .thenReturn(Optional.of(generation));
        when(diaryRepository.findByIdIncludingDeletedForUpdate(generation.getDiaryId()))
                .thenReturn(Optional.of(diary));
        when(generationUsageService.restoreUsage(USER_ID, USAGE_DATE))
                .thenReturn(Optional.of(new GenerationUsage(USAGE_DATE, 0, 3)));

        GenerationErrorCode result = completionService.fail(
                generation.getId(),
                GenerationErrorCode.AI_PROVIDER_TIMEOUT
        );

        assertThat(result).isEqualTo(GenerationErrorCode.AI_PROVIDER_TIMEOUT);
        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(generation.getCompletedAt()).isEqualTo(NOW);
        verify(diary).delete(NOW);
        verify(generationUsageService).restoreUsage(USER_ID, USAGE_DATE);
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("이미 실패한 생성의 오류 코드를 유지한다")
    void failKeepsExistingErrorCode() {
        DiaryGeneration generation = createGeneration();
        Instant failedAt = NOW.minusSeconds(1);
        generation.fail(GenerationErrorCode.GENERATION_INTERRUPTED, failedAt);
        Diary diary = mock(Diary.class);
        when(diaryGenerationRepository.findByIdForUpdate(generation.getId()))
                .thenReturn(Optional.of(generation));
        when(diaryRepository.findByIdIncludingDeletedForUpdate(generation.getDiaryId()))
                .thenReturn(Optional.of(diary));

        GenerationErrorCode result = completionService.fail(
                generation.getId(),
                GenerationErrorCode.AI_PROVIDER_TIMEOUT
        );

        assertThat(result).isEqualTo(GenerationErrorCode.GENERATION_INTERRUPTED);
        assertThat(generation.getErrorCode()).isEqualTo(GenerationErrorCode.GENERATION_INTERRUPTED);
        verify(diary).delete(failedAt);
        verify(generationUsageService, never()).restoreUsage(any(), any());
        assertNoFinalizations();
    }

    @Test
    @DisplayName("처리 제한 시간을 지난 생성을 중단하며 일기를 함께 폐기한다")
    void interruptStaleGenerationAndDiscardDiary() {
        DiaryGeneration generation = createGeneration(USAGE_DATE);
        Duration processingTimeout = Duration.ofMinutes(15);
        ReflectionTestUtils.setField(
                generation,
                "updatedAt",
                NOW.minus(processingTimeout).minusSeconds(1)
        );
        Diary diary = mock(Diary.class);
        when(diary.getUserId()).thenReturn(USER_ID);
        when(diaryGenerationRepository.findByIdForUpdate(generation.getId()))
                .thenReturn(Optional.of(generation));
        when(diaryRepository.findByIdIncludingDeletedForUpdate(generation.getDiaryId()))
                .thenReturn(Optional.of(diary));
        when(generationUsageService.restoreUsage(USER_ID, USAGE_DATE))
                .thenReturn(Optional.of(new GenerationUsage(USAGE_DATE, 0, 3)));

        boolean interrupted = completionService.interruptIfStale(
                generation.getId(),
                NOW,
                processingTimeout
        );

        assertThat(interrupted).isTrue();
        assertThat(generation.getErrorCode())
                .isEqualTo(GenerationErrorCode.GENERATION_INTERRUPTED);
        verify(diary).delete(NOW);
        verify(generationUsageService).restoreUsage(USER_ID, USAGE_DATE);
    }

    @Test
    @DisplayName("대기 만료 시 일기를 숨기고 작업에 기록된 날짜의 사용량을 돌려준다")
    void expirePendingGenerationAndRestoreOriginalUsageDate() {
        LocalDate originalUsageDate = USAGE_DATE.minusDays(1);
        DiaryGeneration generation = createPendingGeneration(originalUsageDate, NOW.minusSeconds(120));
        Diary diary = mock(Diary.class);
        when(diary.getUserId()).thenReturn(USER_ID);
        when(diaryGenerationRepository.findByIdForUpdate(generation.getId())).thenReturn(Optional.of(generation));
        when(diaryRepository.findByIdIncludingDeletedForUpdate(generation.getDiaryId())).thenReturn(Optional.of(diary));

        boolean expired = completionService.expirePendingIfStale(generation.getId(), NOW, Duration.ofSeconds(120));

        assertThat(expired).isTrue();
        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(generation.getErrorCode()).isEqualTo(GenerationErrorCode.GENERATION_PENDING_TIMEOUT);
        assertThat(generation.getCompletedAt()).isEqualTo(NOW);
        verify(diary).delete(NOW);
        verify(generationUsageService).restoreUsage(USER_ID, originalUsageDate);
        verify(events).publishEvent(new DiaryGenerationPendingExpiredEvent(generation.getId()));
        assertNoFinalizations();
    }

    @Test
    @DisplayName("기한이 남은 대기 작업은 일기와 사용량을 변경하지 않는다")
    void doNotExpireRecentPendingGeneration() {
        DiaryGeneration generation = createPendingGeneration(USAGE_DATE, NOW.minusSeconds(120).plusNanos(1));
        when(diaryGenerationRepository.findByIdForUpdate(generation.getId())).thenReturn(Optional.of(generation));

        assertThat(completionService.expirePendingIfStale(generation.getId(), NOW, Duration.ofSeconds(120))).isFalse();

        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.PENDING);
        verifyNoInteractions(diaryRepository, generationUsageService, events);
        assertNoFinalizations();
    }

    @ParameterizedTest
    @EnumSource(value = GenerationStatus.class, names = {"PROCESSING", "SUCCEEDED", "FAILED"})
    @DisplayName("행 잠금 후 이미 실행 중이거나 종료된 작업이면 대기 만료를 건너뛴다")
    void skipPendingExpiryForNonPendingGeneration(GenerationStatus status) {
        DiaryGeneration generation = createGeneration(USAGE_DATE);
        ReflectionTestUtils.setField(generation, "createdAt", NOW.minusSeconds(180));
        if (status == GenerationStatus.SUCCEEDED) {
            generation.succeed(createStoryboard(), "generated/winner.png", NOW.minusSeconds(1));
        }
        if (status == GenerationStatus.FAILED) {
            generation.fail(GenerationErrorCode.AI_PROVIDER_ERROR, NOW.minusSeconds(1));
        }
        when(diaryGenerationRepository.findByIdForUpdate(generation.getId())).thenReturn(Optional.of(generation));

        assertThat(completionService.expirePendingIfStale(generation.getId(), NOW, Duration.ofSeconds(120))).isFalse();

        assertThat(generation.getStatus()).isEqualTo(status);
        verifyNoInteractions(diaryRepository, generationUsageService, events);
        assertNoFinalizations();
    }

    @Test
    @DisplayName("사용 날짜가 없는 대기 작업은 만료해도 회원 사용량을 복구하지 않는다")
    void expirePendingGenerationWithoutMemberUsage() {
        DiaryGeneration generation = createPendingGeneration(null, NOW.minusSeconds(120));
        Diary diary = mock(Diary.class);
        when(diaryGenerationRepository.findByIdForUpdate(generation.getId())).thenReturn(Optional.of(generation));
        when(diaryRepository.findByIdIncludingDeletedForUpdate(generation.getDiaryId())).thenReturn(Optional.of(diary));

        assertThat(completionService.expirePendingIfStale(generation.getId(), NOW, Duration.ofSeconds(120))).isTrue();

        verify(diary).delete(NOW);
        verifyNoInteractions(generationUsageService);
    }

    @Test
    @DisplayName("같은 대기 작업을 다시 만료해도 일기 숨김과 사용량 복구를 반복하지 않는다")
    void expirePendingGenerationOnlyOnce() {
        DiaryGeneration generation = createPendingGeneration(USAGE_DATE, NOW.minusSeconds(120));
        Diary diary = mock(Diary.class);
        when(diary.getUserId()).thenReturn(USER_ID);
        when(diaryGenerationRepository.findByIdForUpdate(generation.getId())).thenReturn(Optional.of(generation));
        when(diaryRepository.findByIdIncludingDeletedForUpdate(generation.getDiaryId())).thenReturn(Optional.of(diary));

        assertThat(completionService.expirePendingIfStale(generation.getId(), NOW, Duration.ofSeconds(120))).isTrue();
        assertThat(completionService.expirePendingIfStale(
                generation.getId(), NOW.plusSeconds(1), Duration.ofSeconds(120)
        )).isFalse();

        assertThat(generation.getCompletedAt()).isEqualTo(NOW);
        verify(diary).delete(NOW);
        verify(generationUsageService).restoreUsage(USER_ID, USAGE_DATE);
        verify(events).publishEvent(new DiaryGenerationPendingExpiredEvent(generation.getId()));
    }

    @Test
    @DisplayName("대기 만료 지표는 실패 저장이 커밋된 뒤에 한 번 기록한다")
    void recordPendingExpiryAfterCommit() {
        DiaryGeneration generation = createPendingGeneration(null, NOW.minusSeconds(120));
        Diary diary = mock(Diary.class);
        when(diaryGenerationRepository.findByIdForUpdate(generation.getId())).thenReturn(Optional.of(generation));
        when(diaryRepository.findByIdIncludingDeletedForUpdate(generation.getDiaryId())).thenReturn(Optional.of(diary));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThat(completionService.expirePendingIfStale(generation.getId(), NOW, Duration.ofSeconds(120))).isTrue();
            assertThat(completionService.expirePendingIfStale(generation.getId(), NOW, Duration.ofSeconds(120))).isFalse();

            assertNoFinalizations();
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            assertThat(finalizationCount("FAILED", "GENERATION_PENDING_TIMEOUT")).isEqualTo(1.0);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("최종 상태 지표는 트랜잭션 커밋 뒤에만 기록한다")
    void recordFinalizationAfterCommit() {
        DiaryGeneration generation = createGeneration();
        when(diaryGenerationRepository.findByIdForUpdate(generation.getId()))
                .thenReturn(Optional.of(generation));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            completionService.succeed(generation.getId(), createStoryboard(), "generated/comic.png", null);

            assertNoFinalizations();
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            assertThat(finalizationCount("SUCCEEDED", "none")).isEqualTo(1.0);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("실패 및 중단도 FAILED와 고정 오류 코드로 커밋 후 집계한다")
    void recordFailedAndInterruptedFinalizationsAfterCommit() {
        DiaryGeneration failedGeneration = createGeneration();
        DiaryGeneration interruptedGeneration = createGeneration();
        Diary failedDiary = mock(Diary.class);
        Diary interruptedDiary = mock(Diary.class);
        ReflectionTestUtils.setField(interruptedGeneration, "updatedAt", NOW.minus(Duration.ofMinutes(16)));
        when(diaryGenerationRepository.findByIdForUpdate(failedGeneration.getId()))
                .thenReturn(Optional.of(failedGeneration));
        when(diaryGenerationRepository.findByIdForUpdate(interruptedGeneration.getId()))
                .thenReturn(Optional.of(interruptedGeneration));
        when(diaryRepository.findByIdIncludingDeletedForUpdate(failedGeneration.getDiaryId()))
                .thenReturn(Optional.of(failedDiary));
        when(diaryRepository.findByIdIncludingDeletedForUpdate(interruptedGeneration.getDiaryId()))
                .thenReturn(Optional.of(interruptedDiary));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            completionService.fail(failedGeneration.getId(), GenerationErrorCode.AI_PROVIDER_TIMEOUT);
            completionService.interruptIfStale(interruptedGeneration.getId(), NOW, Duration.ofMinutes(15));

            assertNoFinalizations();
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            assertThat(finalizationCount("FAILED", "AI_PROVIDER_TIMEOUT")).isEqualTo(1.0);
            assertThat(finalizationCount("FAILED", "GENERATION_INTERRUPTED")).isEqualTo(1.0);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private double finalizationCount(String status, String errorCode) {
        return meterRegistry.get("harudle.generation.finalizations")
                .tags("status", status, "errorCode", errorCode)
                .counter()
                .count();
    }

    private void assertNoFinalizations() {
        assertThat(finalizationCount("SUCCEEDED", "none")).isZero();
        for (GenerationErrorCode errorCode : GenerationErrorCode.values()) {
            assertThat(finalizationCount("FAILED", errorCode.name())).isZero();
        }
    }

    private DiaryGeneration createPendingGeneration(LocalDate usageDate, Instant createdAt) {
        DiaryGeneration generation = DiaryGeneration.pending(
                UUID.randomUUID(), 1L, UUID.randomUUID(), "a".repeat(64), usageDate
        );
        ReflectionTestUtils.setField(generation, "createdAt", createdAt);
        return generation;
    }

    private DiaryGeneration createGeneration() {
        return createGeneration(null);
    }

    private DiaryGeneration createGeneration(LocalDate usageDate) {
        return DiaryGeneration.start(
                UUID.randomUUID(),
                1L,
                UUID.randomUUID(),
                "a".repeat(64),
                usageDate
        );
    }

    private Storyboard createStoryboard() {
        return new Storyboard(
                "친구와 보낸 하루",
                "같은 주인공이 모든 패널에 등장한다.",
                List.of(
                        createPanel(1, "첫 번째 캡션"),
                        createPanel(2, "두 번째 캡션"),
                        createPanel(3, "세 번째 캡션"),
                        createPanel(4, "네 번째 캡션")
                )
        );
    }

    private StoryPanel createPanel(int panelNumber, String caption) {
        return new StoryPanel(panelNumber, caption, "장면", "등장인물", "감정", List.of());
    }
}
