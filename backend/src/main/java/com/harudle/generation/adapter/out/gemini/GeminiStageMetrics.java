package com.harudle.generation.adapter.out.gemini;

import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One application-level observation per Gemini stage call, including SDK retries. */
public final class GeminiStageMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(GeminiStageMetrics.class);
    private final MeterRegistry meterRegistry;

    public GeminiStageMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    void record(String stage, @Nullable GeminiFailureType failureType,
            long durationNanos, @Nullable GenerateContentResponse response) {
        try {
            recordSafely(stage, failureType, durationNanos, response);
        } catch (RuntimeException exception) {
            LOGGER.warn("event=metrics_recording_failed component=gemini stage={} exceptionType={}",
                    stage, exception.getClass().getSimpleName());
        }
    }

    private void recordSafely(String stage, @Nullable GeminiFailureType failureType,
            long durationNanos, @Nullable GenerateContentResponse response) {
        String outcome = failureType == null ? "success" : "failure";
        String category = failureType == null ? "none" : failureType.name();
        Counter.builder("harudle.gemini.stage.calls")
                .description("Application-level Gemini stage calls by final outcome")
                .tags("stage", stage, "outcome", outcome, "failureType", category)
                .register(meterRegistry)
                .increment();
        Timer.builder("harudle.gemini.stage.duration")
                .description("Gemini stage duration including request preparation and SDK retries")
                .tags("stage", stage, "outcome", outcome)
                .register(meterRegistry)
                .record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        recordFinishReason(stage, response);
        recordTokenUsage(stage, response);
    }

    private void recordFinishReason(String stage, @Nullable GenerateContentResponse response) {
        String reason = GeminiFailureType.finishReason(response);
        if (reason == null) {
            return;
        }
        String boundedReason = switch (reason) {
            case "STOP", "MAX_TOKENS", "SAFETY", "RECITATION", "PROHIBITED_CONTENT" -> reason;
            default -> "OTHER";
        };
        meterRegistry.counter("harudle.gemini.finish.reason",
                "stage", stage, "reason", boundedReason).increment();
    }

    private void recordTokenUsage(String stage, @Nullable GenerateContentResponse response) {
        if (response == null) {
            return;
        }
        Optional<GenerateContentResponseUsageMetadata> usage = response.usageMetadata();
        if (usage == null || usage.isEmpty()) {
            return;
        }
        GenerateContentResponseUsageMetadata metadata = usage.get();
        addTokens(stage, "prompt", metadata.promptTokenCount());
        addTokens(stage, "candidate", metadata.candidatesTokenCount());
        addTokens(stage, "thought", metadata.thoughtsTokenCount());
        addTokens(stage, "total", metadata.totalTokenCount());
    }

    private void addTokens(String stage, String kind, Optional<Integer> tokenCount) {
        tokenCount.filter(count -> count > 0).ifPresent(count ->
                meterRegistry.counter("harudle.gemini.tokens", "stage", stage, "kind", kind)
                        .increment(count));
    }
}
