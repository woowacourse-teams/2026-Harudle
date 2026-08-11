package com.harudle.generation.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.generation.configuration.GenerationLifecycleProperties;
import com.harudle.generation.repository.DiaryGenerationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private DiaryGenerationCleanupScheduler scheduler;

    @Test
    @DisplayName("처리 제한 시간을 지난 그림일기 생성 작업을 주기적으로 실패 처리한다")
    void expireStaleProcessingGenerations() {
        scheduler = new DiaryGenerationCleanupScheduler(
                diaryGenerationRepository,
                generationLifecycleProperties,
                clock
        );
        when(generationLifecycleProperties.processingTimeout()).thenReturn(PROCESSING_TIMEOUT);

        scheduler.expireStaleProcessingGenerations();

        verify(diaryGenerationRepository).expireProcessingGenerations(
                NOW.minus(PROCESSING_TIMEOUT),
                NOW
        );
    }
}
