package com.harudle.generation.diary.service;

import com.harudle.generation.diary.service.dto.CompletedDiaryGeneration;
import com.harudle.generation.diary.service.dto.GenerateDiaryImageCommand;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Records one outcome for each newly claimed generation execution. */
public final class ObservedDiaryGenerationExecutor implements DiaryGenerationExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ObservedDiaryGenerationExecutor.class);
    private static final String GENERATION_ID_MDC_KEY = "generationId";
    private static final String RESULT_RETURNED = "returned";
    private static final String RESULT_THREW = "threw";

    private final DiaryGenerationExecutor delegate;
    private final MeterRegistry meterRegistry;

    public ObservedDiaryGenerationExecutor(DiaryGenerationExecutor delegate, MeterRegistry meterRegistry) {
        this.delegate = Objects.requireNonNull(delegate, "생성 실행기가 필요합니다.");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "지표 수집기가 필요합니다.");
    }

    @Override
    public boolean isConfigured() {
        return delegate.isConfigured();
    }

    @Override
    public CompletedDiaryGeneration generate(GenerateDiaryImageCommand command, UUID generationId) {
        long startedAt = System.nanoTime();
        String previousGenerationId = MDC.get(GENERATION_ID_MDC_KEY);
        MDC.put(GENERATION_ID_MDC_KEY, generationId.toString());
        String result = RESULT_THREW;
        try {
            CompletedDiaryGeneration completed = delegate.generate(command, generationId);
            result = RESULT_RETURNED;
            return completed;
        } finally {
            try {
                record(result, System.nanoTime() - startedAt);
            } catch (RuntimeException exception) {
                LOGGER.warn("event=metrics_recording_failed component=generation exceptionType={}",
                        exception.getClass().getSimpleName());
            } finally {
                if (previousGenerationId == null) {
                    MDC.remove(GENERATION_ID_MDC_KEY);
                } else {
                    MDC.put(GENERATION_ID_MDC_KEY, previousGenerationId);
                }
            }
        }
    }

    private void record(String result, long durationNanos) {
        Counter.builder("harudle.generation.executions")
                .description("New generation executions by call outcome")
                .tag("result", result)
                .register(meterRegistry)
                .increment();
        Timer.builder("harudle.generation.duration")
                .description("End-to-end duration of a new generation execution")
                .tag("result", result)
                .register(meterRegistry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }
}
