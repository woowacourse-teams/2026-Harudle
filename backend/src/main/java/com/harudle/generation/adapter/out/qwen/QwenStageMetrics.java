package com.harudle.generation.adapter.out.qwen;

import com.harudle.generation.adapter.out.qwen.QwenChatClient.Completion;
import com.harudle.generation.adapter.out.qwen.QwenChatClient.Timing;
import com.harudle.generation.diary.domain.GenerationTokenUsage;
import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One observation per storyboard invocation, including response validation. */
public final class QwenStageMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(QwenStageMetrics.class);
    private final MeterRegistry meterRegistry;
    private final String model;

    public QwenStageMetrics(MeterRegistry meterRegistry, String model) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
        this.model = model != null && model.matches("[A-Za-z0-9_.-]{1,128}") ? model : "other";
    }

    void record(@Nullable AiGenerationErrorType failureType, long durationNanos,
            @Nullable Completion response, @Nullable GenerationTokenUsage usage) {
        try {
            recordSafely(failureType, durationNanos, response, usage);
        } catch (RuntimeException exception) {
            LOGGER.warn("event=metrics_recording_failed component=qwen stage=storyboard exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private void recordSafely(@Nullable AiGenerationErrorType failureType, long durationNanos,
            @Nullable Completion response, @Nullable GenerationTokenUsage usage) {
        String outcome = failureType == null ? "success" : "failure";
        String category = failureType == null ? "none" : failureType.name();
        meterRegistry.counter("harudle.qwen.stage.calls", "stage", "storyboard", "model", model,
                "outcome", outcome, "failureType", category).increment();
        meterRegistry.timer("harudle.qwen.stage.duration", "stage", "storyboard", "model", model,
                "outcome", outcome).record(durationNanos, TimeUnit.NANOSECONDS);
        recordUsage(usage);
        Timing timing = response == null ? null : response.timing();
        recordTiming(timing);
        logCompletion(outcome, category, durationNanos, timing, usage);
    }

    private void recordUsage(@Nullable GenerationTokenUsage usage) {
        if (usage == null) {
            return;
        }
        addTokens("prompt", usage.promptTokenCount());
        addTokens("candidate", usage.candidateTokenCount());
        addTokens("thought", usage.thoughtTokenCount());
        addTokens("total", usage.totalTokenCount());
    }

    private void addTokens(String kind, @Nullable Integer count) {
        if (count != null && count > 0) {
            meterRegistry.counter("harudle.qwen.tokens", "stage", "storyboard", "model", model,
                    "kind", kind).increment(count);
        }
    }

    private void recordTiming(@Nullable Timing timing) {
        if (timing == null) {
            return;
        }
        recordResponseTime("headers", timing.responseHeadersSeconds());
        recordResponseTime("first_reasoning", timing.firstReasoningSeconds());
        recordResponseTime("first_answer", timing.firstAnswerSeconds());
        recordResponseTime("completed", timing.responseCompletedSeconds());
    }

    private void recordResponseTime(String phase, @Nullable Double seconds) {
        if (seconds != null && Double.isFinite(seconds) && seconds >= 0) {
            meterRegistry.timer("harudle.qwen.response.duration", "stage", "storyboard", "model", model,
                    "phase", phase).record((long) (seconds * 1_000_000_000L), TimeUnit.NANOSECONDS);
        }
    }

    private void logCompletion(String outcome, String category, long durationNanos,
            @Nullable Timing timing, @Nullable GenerationTokenUsage usage) {
        LOGGER.atInfo()
                .addKeyValue("event", "generation_stage_completed")
                .addKeyValue("provider", "qwen")
                .addKeyValue("stage", "storyboard")
                .addKeyValue("model", model)
                .addKeyValue("outcome", outcome)
                .addKeyValue("failureType", category)
                .addKeyValue("durationMs", TimeUnit.NANOSECONDS.toMillis(durationNanos))
                .addKeyValue("responseHeadersSeconds", timing == null ? null : timing.responseHeadersSeconds())
                .addKeyValue("firstReasoningSeconds", timing == null ? null : timing.firstReasoningSeconds())
                .addKeyValue("firstAnswerSeconds", timing == null ? null : timing.firstAnswerSeconds())
                .addKeyValue("responseCompletedSeconds", timing == null ? null : timing.responseCompletedSeconds())
                .addKeyValue("promptTokenCount", usage == null ? null : usage.promptTokenCount())
                .addKeyValue("candidateTokenCount", usage == null ? null : usage.candidateTokenCount())
                .addKeyValue("thoughtTokenCount", usage == null ? null : usage.thoughtTokenCount())
                .addKeyValue("totalTokenCount", usage == null ? null : usage.totalTokenCount())
                .log("event=generation_stage_completed provider=qwen stage=storyboard model={} "
                                + "outcome={} failureType={} durationMs={}",
                        model, outcome, category, TimeUnit.NANOSECONDS.toMillis(durationNanos));
    }
}
