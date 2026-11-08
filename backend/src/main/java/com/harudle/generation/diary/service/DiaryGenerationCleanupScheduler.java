package com.harudle.generation.diary.service;

import com.harudle.generation.config.GenerationLifecycleProperties;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import java.time.Clock;
import java.time.Duration;
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
        int failedCount = 0;
        try {
            List<UUID> generationIds = findStaleGenerationIds(currentTime);
            candidateCount = generationIds.size();
            for (UUID generationId : generationIds) {
                try {
                    if (completionService.interruptIfStale(
                            generationId,
                            currentTime,
                            generationLifecycleProperties.processingTimeout()
                    )) {
                        expiredCount++;
                    }
                } catch (RuntimeException exception) {
                    failedCount++;
                    logCleanupFailure("generation_processing_expiry_failed", generationId, exception);
                }
            }

            log.atInfo()
                    .addKeyValue("event", "generation_cleanup_run")
                    .addKeyValue("candidateCount", candidateCount)
                    .addKeyValue("interruptedCount", expiredCount)
                    .addKeyValue("failedCount", failedCount)
                    .log("event=generation_cleanup_run candidateCount={} interruptedCount={} failedCount={}",
                            candidateCount, expiredCount, failedCount);
        } catch (RuntimeException exception) {
            log.atError()
                    .addKeyValue("event", "generation_cleanup_failed")
                    .addKeyValue("candidateCount", candidateCount)
                    .addKeyValue("interruptedCount", expiredCount)
                    .addKeyValue("failedCount", failedCount)
                    .addKeyValue("exceptionType", exception.getClass().getSimpleName())
                    .log("event=generation_cleanup_failed candidateCount={} interruptedCount={} failedCount={} exceptionType={}",
                            candidateCount, expiredCount, failedCount, exception.getClass().getSimpleName());
            throw exception;
        }
    }

    @Scheduled(
            fixedDelayString = "${harudle.generation.lifecycle.pending-cleanup-interval:5s}",
            initialDelayString = "${harudle.generation.lifecycle.pending-cleanup-interval:5s}"
    )
    public void expireStalePendingGenerations() {
        Instant currentTime = clock.instant();
        Duration pendingTimeout = generationLifecycleProperties.pendingTimeout();
        int candidateCount = 0;
        int expiredCount = 0;
        int failedCount = 0;
        try {
            List<UUID> generationIds = diaryGenerationRepository.findStalePendingIds(
                    currentTime.minus(pendingTimeout), CLEANUP_BATCH_SIZE
            );
            candidateCount = generationIds.size();
            for (UUID generationId : generationIds) {
                try {
                    if (completionService.expirePendingIfStale(generationId, currentTime, pendingTimeout)) {
                        expiredCount++;
                    }
                } catch (RuntimeException exception) {
                    failedCount++;
                    logCleanupFailure("generation_pending_expiry_failed", generationId, exception);
                }
            }

            if (candidateCount > 0) {
                log.atInfo()
                        .addKeyValue("event", "generation_pending_cleanup_run")
                        .addKeyValue("candidateCount", candidateCount)
                        .addKeyValue("expiredCount", expiredCount)
                        .addKeyValue("failedCount", failedCount)
                        .log("event=generation_pending_cleanup_run candidateCount={} expiredCount={} failedCount={}",
                                candidateCount, expiredCount, failedCount);
            }
        } catch (RuntimeException exception) {
            log.atError()
                    .addKeyValue("event", "generation_pending_cleanup_failed")
                    .addKeyValue("candidateCount", candidateCount)
                    .addKeyValue("expiredCount", expiredCount)
                    .addKeyValue("failedCount", failedCount)
                    .addKeyValue("exceptionType", exception.getClass().getSimpleName())
                    .log("event=generation_pending_cleanup_failed candidateCount={} expiredCount={} failedCount={} exceptionType={}",
                            candidateCount, expiredCount, failedCount, exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private void logCleanupFailure(String event, UUID generationId, RuntimeException exception) {
        log.atWarn()
                .addKeyValue("event", event)
                .addKeyValue("generationId", generationId)
                .addKeyValue("exceptionType", exception.getClass().getSimpleName())
                .log("event={} generationId={} exceptionType={}",
                        event, generationId, exception.getClass().getSimpleName());
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
