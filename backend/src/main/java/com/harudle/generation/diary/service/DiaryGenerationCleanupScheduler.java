package com.harudle.generation.diary.service;

import com.harudle.generation.config.GenerationLifecycleProperties;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
        Instant currentTime = clock.instant();
        int candidateCount = 0;
        int expiredCount = 0;
        try {
            List<UUID> generationIds = findStaleGenerationIds(currentTime);
            candidateCount = generationIds.size();
            for (UUID generationId : generationIds) {
                if (completionService.interruptIfStale(
                        generationId,
                        currentTime,
                        generationLifecycleProperties.processingTimeout()
                )) {
                    expiredCount++;
                }
            }

            log.atInfo()
                    .addKeyValue("event", "generation_cleanup_run")
                    .addKeyValue("candidateCount", candidateCount)
                    .addKeyValue("interruptedCount", expiredCount)
                    .log("event=generation_cleanup_run candidateCount={} interruptedCount={}",
                            candidateCount, expiredCount);
        } catch (RuntimeException exception) {
            log.atError()
                    .addKeyValue("event", "generation_cleanup_failed")
                    .addKeyValue("candidateCount", candidateCount)
                    .addKeyValue("interruptedCount", expiredCount)
                    .addKeyValue("exceptionType", exception.getClass().getSimpleName())
                    .log("event=generation_cleanup_failed candidateCount={} interruptedCount={} exceptionType={}",
                            candidateCount, expiredCount, exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private List<UUID> findStaleGenerationIds(Instant currentTime) {
        Instant expiredBefore = currentTime.minus(generationLifecycleProperties.processingTimeout());
        return diaryGenerationRepository.findStaleProcessingIds(
                GenerationStatus.PROCESSING,
                expiredBefore,
                PageRequest.of(0, CLEANUP_BATCH_SIZE)
        );
    }
}
