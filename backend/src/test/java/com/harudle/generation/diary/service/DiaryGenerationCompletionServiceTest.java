package com.harudle.generation.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.diary.domain.Diary;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.domain.GenerationErrorCode;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.domain.GenerationTokenUsage;
import com.harudle.generation.diary.domain.StoryPanel;
import com.harudle.generation.diary.domain.Storyboard;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
                new GenerationLifecycleMetrics(meterRegistry)
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
