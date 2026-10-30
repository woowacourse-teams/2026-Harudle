package com.harudle.generation.diary.service.image;

import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.exception.AiProviderFailureType;
import com.harudle.generation.diary.service.port.DiaryImageGenerator;
import com.harudle.generation.diary.service.port.dto.DiaryImageGenerationRequest;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FallbackDiaryImageGenerator implements DiaryImageGenerator {

    private static final Logger log = LoggerFactory.getLogger(FallbackDiaryImageGenerator.class);
    private final DiaryImageGenerator primary;
    private final DiaryImageGenerator fallback;
    private final MeterRegistry metrics;

    public FallbackDiaryImageGenerator(
            DiaryImageGenerator primary, DiaryImageGenerator fallback, MeterRegistry metrics
    ) {
        this.primary = Objects.requireNonNull(primary);
        this.fallback = Objects.requireNonNull(fallback);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public GeneratedImage generate(DiaryImageGenerationRequest request) {
        try {
            return primary.generate(request);
        } catch (AiGenerationException primaryFailure) {
            String reason = switch (primaryFailure.errorType()) {
                case RATE_LIMITED -> "rate_limited";
                case TIMEOUT -> "timeout";
                default -> null;
            };
            if (reason == null
                    || primaryFailure.providerFailureType() == AiProviderFailureType.CANCELLED
                    || Thread.currentThread().isInterrupted()) {
                throw primaryFailure;
            }
            log.info("Image provider fallback: primary=gemini fallback=openai reason={}", reason);
            recordFallbackMetric(reason);
            try {
                return fallback.generate(request);
            } catch (RuntimeException fallbackFailure) {
                if (fallbackFailure != primaryFailure) {
                    fallbackFailure.addSuppressed(primaryFailure);
                }
                throw fallbackFailure;
            }
        }
    }

    private void recordFallbackMetric(String reason) {
        try {
            metrics.counter("harudle.image.fallback.calls", "provider", "openai", "reason", reason).increment();
        } catch (RuntimeException exception) {
            log.warn("event=metrics_recording_failed component=image_fallback reason={} exceptionType={}",
                    reason, exception.getClass().getSimpleName());
        }
    }
}
