package com.harudle.generation.diary.service;

import com.harudle.generation.diary.domain.GenerationErrorCode;
import com.harudle.generation.diary.domain.GenerationStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Observations of committed generation state changes and unexpected execution failures. */
@Component
public final class GenerationLifecycleMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(GenerationLifecycleMetrics.class);
    private final MeterRegistry meterRegistry;

    GenerationLifecycleMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        for (Phase phase : Phase.values()) {
            unexpectedFailureCounter(phase);
        }
        finalizationCounter(GenerationStatus.SUCCEEDED, "none");
        for (GenerationErrorCode errorCode : GenerationErrorCode.values()) {
            finalizationCounter(GenerationStatus.FAILED, errorCode.name());
        }
    }

    enum Phase {
        STORYBOARD("storyboard"),
        REFERENCE_LOAD("reference_load"),
        IMAGE_GENERATION("image_generation"),
        IMAGE_STORE("image_store"),
        COMPLETION("completion");

        private final String tag;

        Phase(String tag) {
            this.tag = tag;
        }
    }

    void finalizedAfterCommit(UUID generationId, GenerationStatus status, GenerationErrorCode errorCode) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        recordFinalization(generationId, status, errorCode);
                    } catch (RuntimeException exception) {
                        warnRecordingFailure("generation_finalization", exception);
                    }
                }
            });
        } catch (RuntimeException exception) {
            warnRecordingFailure("generation_finalization", exception);
        }
    }

    void unexpectedFailure(UUID generationId, Phase phase, RuntimeException exception) {
        String exceptionType = exception.getClass().getSimpleName();
        String causeType = rootCause(exception).getClass().getSimpleName();
        RuntimeException safeTrace = new RuntimeException("Unexpected generation failure");
        safeTrace.setStackTrace(exception.getStackTrace());
        try {
            LOGGER.atError()
                    .addKeyValue("event", "generation_unexpected_failure")
                    .addKeyValue("generationId", generationId.toString())
                    .addKeyValue("phase", phase.tag)
                    .addKeyValue("exceptionType", exceptionType)
                    .addKeyValue("causeType", causeType)
                    .setCause(safeTrace)
                    .log("event=generation_unexpected_failure generationId={} phase={} exceptionType={} causeType={}",
                            generationId, phase.tag, exceptionType, causeType);
            unexpectedFailureCounter(phase).increment();
        } catch (RuntimeException metricsException) {
            warnRecordingFailure("generation_unexpected_failure", metricsException);
        }
    }

    private void recordFinalization(UUID generationId, GenerationStatus status, GenerationErrorCode errorCode) {
        String error = errorCode == null ? "none" : errorCode.name();
        LOGGER.atInfo()
                .addKeyValue("event", "generation_finalized")
                .addKeyValue("generationId", generationId.toString())
                .addKeyValue("status", status.name())
                .addKeyValue("errorCode", error)
                .log("event=generation_finalized generationId={} status={} errorCode={}",
                        generationId, status, error);
        finalizationCounter(status, error).increment();
    }

    private Counter unexpectedFailureCounter(Phase phase) {
        return Counter.builder("harudle.generation.unexpected.failures")
                .description("Unexpected generation failures by fixed execution phase")
                .tag("phase", phase.tag)
                .register(meterRegistry);
    }

    private Counter finalizationCounter(GenerationStatus status, String errorCode) {
        return Counter.builder("harudle.generation.finalizations")
                .description("Committed generation transitions from PROCESSING to a terminal status")
                .tags("status", status.name(), "errorCode", errorCode)
                .register(meterRegistry);
    }

    private void warnRecordingFailure(String component, RuntimeException exception) {
        try {
            LOGGER.warn("event=metrics_recording_failed component={} exceptionType={}",
                    component, exception.getClass().getSimpleName());
        } catch (RuntimeException ignored) {
            // Observation failures must never change the generation outcome.
        }
    }

    private Throwable rootCause(Throwable exception) {
        Throwable current = exception;
        for (int depth = 0; depth < 16 && current.getCause() != null && current.getCause() != current; depth++) {
            current = current.getCause();
        }
        return current;
    }
}
