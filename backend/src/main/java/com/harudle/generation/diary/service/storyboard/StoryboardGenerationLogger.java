package com.harudle.generation.diary.service.storyboard;

import com.harudle.generation.diary.domain.StoryboardProviderInfo;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.port.StoryboardGenerator;
import com.harudle.generation.diary.service.port.dto.GeneratedStoryboard;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class StoryboardGenerationLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger(StoryboardGenerationLogger.class);

    private StoryboardGenerationLogger() {
    }

    static void attemptCompleted(String provider, int attempt, StoryboardGenerator generator,
            GeneratedStoryboard result, RuntimeException failure, long durationNanos) {
        try {
            StoryboardProviderInfo info = result != null && result.providerInfo() != null
                    ? result.providerInfo() : generator.providerInfo();
            String model = info == null ? "unknown" : info.model();
            String actualProvider = info == null ? provider : info.provider();
            String role = attempt == 1 ? "primary" : "fallback";
            String status = failure == null ? "SUCCEEDED" : "FAILED";
            String errorType = failure instanceof AiGenerationException ai ? ai.errorType().name()
                    : failure == null ? "none" : "INTERNAL_ERROR";
            String detail = failure instanceof AiGenerationException ai ? ai.providerFailureType().name()
                    : failure == null ? "none" : "UNKNOWN";
            long durationMs = TimeUnit.NANOSECONDS.toMillis(Math.max(0, durationNanos));
            LOGGER.atInfo().addKeyValue("event", "storyboard_attempt_completed")
                    .addKeyValue("provider", actualProvider).addKeyValue("model", model)
                    .addKeyValue("attempt", attempt).addKeyValue("role", role).addKeyValue("status", status)
                    .addKeyValue("durationMs", durationMs).addKeyValue("errorType", errorType)
                    .addKeyValue("providerFailureType", detail)
                    .log("event=storyboard_attempt_completed provider={} model={} attempt={} role={} status={} "
                                    + "durationMs={} errorType={} providerFailureType={}",
                            actualProvider, model, attempt, role, status, durationMs, errorType, detail);
        } catch (RuntimeException exception) {
            observationFailed(exception);
        }
    }

    static void fallbackTriggered(String primaryProvider, StoryboardGenerator primary,
            String fallbackProvider, StoryboardGenerator fallback, AiGenerationException failure) {
        try {
            StoryboardProviderInfo primaryInfo = primary.providerInfo();
            StoryboardProviderInfo fallbackInfo = fallback.providerInfo();
            String primaryModel = primaryInfo == null ? "unknown" : primaryInfo.model();
            String fallbackModel = fallbackInfo == null ? "unknown" : fallbackInfo.model();
            LOGGER.atInfo().addKeyValue("event", "storyboard_fallback_triggered")
                    .addKeyValue("primaryProvider", primaryProvider).addKeyValue("primaryModel", primaryModel)
                    .addKeyValue("fallbackProvider", fallbackProvider).addKeyValue("fallbackModel", fallbackModel)
                    .addKeyValue("reason", failure.errorType().name())
                    .addKeyValue("providerFailureType", failure.providerFailureType().name())
                    .log("event=storyboard_fallback_triggered primaryProvider={} fallbackProvider={} reason={} "
                                    + "providerFailureType={}",
                            primaryProvider, fallbackProvider, failure.errorType(), failure.providerFailureType());
        } catch (RuntimeException exception) {
            observationFailed(exception);
        }
    }

    private static void observationFailed(RuntimeException exception) {
        try {
            LOGGER.warn("event=storyboard_observation_failed exceptionType={}", exception.getClass().getSimpleName());
        } catch (RuntimeException ignored) {
            // Observation failures must never change the generation outcome.
        }
    }
}
