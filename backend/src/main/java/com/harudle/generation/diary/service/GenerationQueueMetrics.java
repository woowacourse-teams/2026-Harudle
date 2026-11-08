package com.harudle.generation.diary.service;

import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public final class GenerationQueueMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(GenerationQueueMetrics.class);

    private final DiaryGenerationRepository generationRepository;
    private final Timer queueWait;
    private final Counter capacityRejections;

    GenerationQueueMetrics(MeterRegistry registry, DiaryGenerationRepository generationRepository) {
        this.generationRepository = generationRepository;
        this.queueWait = Timer.builder("harudle.generation.queue.wait")
                .description("Time from generation persistence to a committed worker claim")
                .register(registry);
        this.capacityRejections = Counter.builder("harudle.generation.capacity.rejections")
                .description("New generation requests rejected because unfinished work is at capacity")
                .register(registry);
        for (GenerationStatus status : List.of(GenerationStatus.PENDING, GenerationStatus.PROCESSING)) {
            Gauge.builder("harudle.generation.queue.size", this, metrics -> metrics.countGenerations(status))
                    .description("Current persisted generation count by unfinished status")
                    .tag("status", status.name())
                    .register(registry);
        }
    }

    void claimedAfterCommit(Instant createdAt, Instant claimedAt) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        try {
            Duration wait = Duration.between(createdAt, claimedAt);
            Duration nonNegativeWait = wait.isNegative() ? Duration.ZERO : wait;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        queueWait.record(nonNegativeWait);
                    } catch (RuntimeException exception) {
                        warnRecordingFailure("generation_queue_wait", exception);
                    }
                }
            });
        } catch (RuntimeException exception) {
            warnRecordingFailure("generation_queue_wait", exception);
        }
    }

    public void capacityRejected() {
        try {
            capacityRejections.increment();
        } catch (RuntimeException exception) {
            warnRecordingFailure("generation_capacity_rejections", exception);
        }
    }

    private double countGenerations(GenerationStatus status) {
        try {
            return generationRepository.countByStatusIn(List.of(status));
        } catch (RuntimeException exception) {
            warnRecordingFailure("generation_queue_size", exception);
            return Double.NaN;
        }
    }

    private void warnRecordingFailure(String component, RuntimeException exception) {
        try {
            LOGGER.warn("event=metrics_recording_failed component={} exceptionType={}",
                    component, exception.getClass().getSimpleName());
        } catch (RuntimeException ignored) {
            // Observation failures must never change generation processing.
        }
    }
}
