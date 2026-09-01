package com.harudle.generation.diary.service;

import com.harudle.generation.config.GenerationLifecycleProperties;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class DiaryGenerationCleanupScheduler {

    private static final int CLEANUP_BATCH_SIZE = 100;
    private static final Logger log = LoggerFactory.getLogger(DiaryGenerationCleanupScheduler.class);

    private final DiaryGenerationRepository diaryGenerationRepository;
    private final DiaryGenerationCompletionService completionService;
    private final GenerationLifecycleProperties generationLifecycleProperties;
    private final Clock clock;

    public DiaryGenerationCleanupScheduler(
            DiaryGenerationRepository diaryGenerationRepository,
            DiaryGenerationCompletionService completionService,
            GenerationLifecycleProperties generationLifecycleProperties,
            @Qualifier("serviceClock")
            Clock clock
    ) {
        this.diaryGenerationRepository = diaryGenerationRepository;
        this.completionService = completionService;
        this.generationLifecycleProperties = generationLifecycleProperties;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${harudle.generation.lifecycle.cleanup-interval:1m}",
            initialDelayString = "${harudle.generation.lifecycle.cleanup-interval:1m}"
    )
    public void expireStaleProcessingGenerations() {
        Instant completedAt = clock.instant();
        Instant expiredBefore = completedAt.minus(generationLifecycleProperties.processingTimeout());
        int expiredCount = 0;
        for (UUID generationId : diaryGenerationRepository.findStaleProcessingIds(
                GenerationStatus.PROCESSING,
                expiredBefore,
                PageRequest.of(0, CLEANUP_BATCH_SIZE)
        )) {
            if (completionService.interruptIfStale(
                    generationId,
                    completedAt,
                    generationLifecycleProperties.processingTimeout()
            )) {
                expiredCount++;
            }
        }

        if (expiredCount > 0) {
            log.info("만료된 그림일기 생성 작업을 실패 처리했습니다. expiredCount={}", expiredCount);
        }
    }
}
