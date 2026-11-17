package com.harudle.generation.adapter.out.openai;

import com.harudle.generation.adapter.out.openai.OpenAiChatClient.Completion;
import com.harudle.generation.adapter.out.openai.OpenAiChatClient.Timing;
import com.harudle.generation.diary.domain.GenerationTokenUsage;
import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAI 스토리보드 호출 한 번당 응답 검증까지 포함해 한 번 기록한다.
 * 이미지 지표({@code harudle.image.*})와 이름을 나누기 위해 {@code harudle.openai.*}를 사용한다.
 */
public final class OpenAiStageMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAiStageMetrics.class);
    private final MeterRegistry meterRegistry;
    private final String model;

    public OpenAiStageMetrics(MeterRegistry meterRegistry, String model) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
        this.model = model != null && model.matches("[A-Za-z0-9_.-]{1,128}") ? model : "other";
    }

    void record(@Nullable AiGenerationErrorType failureType, long durationNanos,
            @Nullable Completion response, @Nullable GenerationTokenUsage usage) {
        try {
            recordSafely(failureType, durationNanos, response, usage);
        } catch (RuntimeException exception) {
            LOGGER.warn("event=metrics_recording_failed component=openai stage=storyboard exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private void recordSafely(@Nullable AiGenerationErrorType failureType, long durationNanos,
            @Nullable Completion response, @Nullable GenerationTokenUsage usage) {
        String outcome = failureType == null ? "success" : "failure";
        String category = failureType == null ? "none" : failureType.name();
        meterRegistry.counter("harudle.openai.stage.calls", "stage", "storyboard", "model", model,
                "outcome", outcome, "failureType", category).increment();
        meterRegistry.timer("harudle.openai.stage.duration", "stage", "storyboard", "model", model,
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
            meterRegistry.counter("harudle.openai.tokens", "stage", "storyboard", "model", model,
                    "kind", kind).increment(count);
        }
    }

    private void recordTiming(@Nullable Timing timing) {
        if (timing == null) {
            return;
        }
        recordResponseTime("headers", timing.responseHeadersSeconds());
        recordResponseTime("first_answer", timing.firstAnswerSeconds());
        recordResponseTime("completed", timing.responseCompletedSeconds());
    }

    private void recordResponseTime(String phase, @Nullable Double seconds) {
        if (seconds != null && Double.isFinite(seconds) && seconds >= 0) {
            meterRegistry.timer("harudle.openai.response.duration", "stage", "storyboard", "model", model,
                    "phase", phase).record((long) (seconds * 1_000_000_000L), TimeUnit.NANOSECONDS);
        }
    }

    private void logCompletion(String outcome, String category, long durationNanos,
            @Nullable Timing timing, @Nullable GenerationTokenUsage usage) {
        LOGGER.atInfo()
                .addKeyValue("event", "generation_stage_completed")
                .addKeyValue("provider", "openai")
                .addKeyValue("stage", "storyboard")
                .addKeyValue("model", model)
                .addKeyValue("outcome", outcome)
                .addKeyValue("failureType", category)
                .addKeyValue("durationMs", TimeUnit.NANOSECONDS.toMillis(durationNanos))
                .addKeyValue("responseHeadersSeconds", timing == null ? null : timing.responseHeadersSeconds())
                .addKeyValue("firstAnswerSeconds", timing == null ? null : timing.firstAnswerSeconds())
                .addKeyValue("responseCompletedSeconds", timing == null ? null : timing.responseCompletedSeconds())
                .addKeyValue("promptTokenCount", usage == null ? null : usage.promptTokenCount())
                .addKeyValue("candidateTokenCount", usage == null ? null : usage.candidateTokenCount())
                .addKeyValue("thoughtTokenCount", usage == null ? null : usage.thoughtTokenCount())
                .addKeyValue("totalTokenCount", usage == null ? null : usage.totalTokenCount())
                .log("event=generation_stage_completed provider=openai stage=storyboard model={} "
                                + "outcome={} failureType={} durationMs={}",
                        model, outcome, category, TimeUnit.NANOSECONDS.toMillis(durationNanos));
    }
}
