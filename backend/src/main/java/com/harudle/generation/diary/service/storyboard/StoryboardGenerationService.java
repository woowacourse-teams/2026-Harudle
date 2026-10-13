package com.harudle.generation.diary.service.storyboard;

import com.harudle.generation.config.StoryboardProviderProperties;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.exception.AiProviderFailureType;
import com.harudle.generation.diary.service.port.StoryboardGenerator;
import com.harudle.generation.diary.service.port.dto.GeneratedStoryboard;
import com.harudle.generation.diary.service.port.dto.StoryboardGenerationRequest;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/** Composes provider ports; each provider implementation remains responsible for a single generation. */
public final class StoryboardGenerationService {

    private final StoryboardGenerator primary;
    private final Optional<StoryboardGenerator> fallback;
    private final StoryboardAttemptRunner runner;
    private final StoryboardProviderProperties properties;
    private final LongSupplier nanoTime;

    public StoryboardGenerationService(
            StoryboardGenerator primary,
            Optional<StoryboardGenerator> fallback,
            StoryboardAttemptRunner runner,
            StoryboardProviderProperties properties
    ) {
        this(primary, fallback, runner, properties, System::nanoTime);
    }

    StoryboardGenerationService(
            StoryboardGenerator primary,
            Optional<StoryboardGenerator> fallback,
            StoryboardAttemptRunner runner,
            StoryboardProviderProperties properties,
            LongSupplier nanoTime
    ) {
        this.primary = Objects.requireNonNull(primary);
        this.fallback = Objects.requireNonNull(fallback);
        this.runner = Objects.requireNonNull(runner);
        this.properties = Objects.requireNonNull(properties);
        this.nanoTime = Objects.requireNonNull(nanoTime);
        if (properties.fallbackEnabled() != fallback.isPresent()) {
            throw new IllegalArgumentException("보조 스토리보드 설정과 구현체 구성이 일치해야 합니다.");
        }
        if (fallback.orElse(null) == primary) {
            throw new IllegalArgumentException("메인과 보조 스토리보드 구현체는 서로 달라야 합니다.");
        }
    }

    public GeneratedStoryboard generate(StoryboardGenerationRequest request) {
        Objects.requireNonNull(request);
        StoryboardGenerationBudget budget = StoryboardGenerationBudget.start(properties.totalTimeout(), nanoTime);
        try {
            return runner.run(primary, request, properties.primaryTimeout(), budget);
        } catch (AiGenerationException primaryFailure) {
            if (fallback.isEmpty() || !canFallback(primaryFailure)) {
                throw primaryFailure;
            }
            return runner.run(fallback.orElseThrow(), request, properties.fallbackTimeout(), budget);
        }
    }

    private static boolean canFallback(AiGenerationException failure) {
        AiProviderFailureType detail = failure.providerFailureType();
        if (Thread.currentThread().isInterrupted() || detail == AiProviderFailureType.CANCELLED
                || detail == AiProviderFailureType.AUTH_CONFIGURATION_ERROR
                || detail == AiProviderFailureType.REQUEST_REJECTED) {
            return false;
        }
        return switch (failure.errorType()) {
            case TIMEOUT, RATE_LIMITED, OUTPUT_TRUNCATED, RESPONSE_PROCESSING_ERROR -> true;
            case PROVIDER_ERROR -> detail == AiProviderFailureType.NETWORK_ERROR
                    || detail == AiProviderFailureType.TRANSIENT_SERVER_ERROR;
            case INTERNAL_ERROR -> false;
        };
    }
}
