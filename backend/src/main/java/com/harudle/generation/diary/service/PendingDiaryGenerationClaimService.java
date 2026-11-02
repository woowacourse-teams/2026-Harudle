package com.harudle.generation.diary.service;

import com.harudle.generation.config.GenerationLifecycleProperties;
import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PendingDiaryGenerationClaimService {

    private final DiaryGenerationRepository diaryGenerationRepository;
    private final GenerationQueueMetrics queueMetrics;
    private final GenerationLifecycleProperties lifecycleProperties;
    private final Clock clock;

    public PendingDiaryGenerationClaimService(
            DiaryGenerationRepository diaryGenerationRepository,
            GenerationQueueMetrics queueMetrics,
            GenerationLifecycleProperties lifecycleProperties,
            @Qualifier("serviceClock") Clock clock
    ) {
        this.diaryGenerationRepository = diaryGenerationRepository;
        this.queueMetrics = queueMetrics;
        this.lifecycleProperties = lifecycleProperties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<UUID> claim(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("가져올 생성 작업 수는 1 이상이어야 합니다.");
        }
        Duration pendingTimeout = lifecycleProperties.pendingTimeout();
        List<DiaryGeneration> candidates = diaryGenerationRepository.findPendingForUpdateSkipLocked(
                clock.instant().minus(pendingTimeout), batchSize
        );
        Instant claimedAt = clock.instant();
        Instant createdAfter = claimedAt.minus(pendingTimeout);
        List<DiaryGeneration> generations = candidates.stream()
                .filter(generation -> generation.getCreatedAt().isAfter(createdAfter))
                .toList();
        generations.forEach(generation -> {
            generation.startProcessing();
            queueMetrics.claimedAfterCommit(generation.getCreatedAt(), claimedAt);
        });
        return generations.stream()
                .map(DiaryGeneration::getId)
                .toList();
    }
}
