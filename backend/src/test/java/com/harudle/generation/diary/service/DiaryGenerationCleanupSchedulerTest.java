package com.harudle.generation.diary.service;

import static org.mockito.Mockito.verify;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DiaryGenerationCleanupSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-08-11T03:00:00Z");
    private static final Duration PROCESSING_TIMEOUT = Duration.ofMinutes(30);

    @Mock
    private DiaryGenerationRepository diaryGenerationRepository;

    @Mock
    private GenerationLifecycleProperties generationLifecycleProperties;

    @Mock
    private DiaryGenerationCompletionService completionService;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private DiaryGenerationCleanupScheduler scheduler;

    @Test
    @DisplayName("처리 제한 시간을 지난 그림일기 생성 작업을 주기적으로 실패 처리한다")
    void expireStaleProcessingGenerations() {
        UUID generationId = UUID.randomUUID();
        scheduler = new DiaryGenerationCleanupScheduler(
                diaryGenerationRepository,
                completionService,
                generationLifecycleProperties,
                clock
        );
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
}
