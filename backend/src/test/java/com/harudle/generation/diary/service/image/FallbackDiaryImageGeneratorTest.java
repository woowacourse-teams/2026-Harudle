package com.harudle.generation.diary.service.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.exception.AiProviderFailureType;
import com.harudle.generation.diary.service.port.DiaryImageGenerator;
import com.harudle.generation.diary.service.port.dto.DiaryImageGenerationRequest;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;

class FallbackDiaryImageGeneratorTest {

    private final DiaryImageGenerator primary = mock(DiaryImageGenerator.class);
    private final DiaryImageGenerator fallback = mock(DiaryImageGenerator.class);
    private final DiaryImageGenerationRequest request = mock(DiaryImageGenerationRequest.class);
    private final GeneratedImage image = mock(GeneratedImage.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final DiaryImageGenerator generator = new FallbackDiaryImageGenerator(primary, fallback, metrics);

    @Test
    void primarySuccessDoesNotInvokeFallback() {
        when(primary.generate(request)).thenReturn(image);
        assertThat(generator.generate(request)).isSameAs(image);
        verifyNoInteractions(fallback);
    }

    @ParameterizedTest
    @CsvSource({"RATE_LIMITED,rate_limited", "TIMEOUT,timeout"})
    void eligibleFailureForwardsTheIdenticalRequestOnce(AiGenerationErrorType errorType, String reason) {
        when(primary.generate(request)).thenThrow(new AiGenerationException(errorType, "primary failure"));
        when(fallback.generate(request)).thenReturn(image);

        assertThat(generator.generate(request)).isSameAs(image);

        verify(primary).generate(same(request));
        verify(fallback).generate(same(request));
        assertThat(metrics.get("harudle.image.fallback.calls").tag("reason", reason).counter().count()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = AiGenerationErrorType.class, names = {"RATE_LIMITED", "TIMEOUT"}, mode = EnumSource.Mode.EXCLUDE)
    void otherFailuresDoNotInvokeFallback(AiGenerationErrorType errorType) {
        var failure = new AiGenerationException(errorType, "primary failure");
        when(primary.generate(request)).thenThrow(failure);
        assertThatThrownBy(() -> generator.generate(request)).isSameAs(failure);
        verifyNoInteractions(fallback);
    }

    @ParameterizedTest
    @EnumSource(value = AiGenerationErrorType.class, names = {"RATE_LIMITED", "TIMEOUT"})
    void cancellationDoesNotInvokeFallback(AiGenerationErrorType errorType) {
        var failure = new AiGenerationException(errorType, "cancelled", null,
                AiProviderFailureType.CANCELLED);
        when(primary.generate(request)).thenThrow(failure);
        assertThatThrownBy(() -> generator.generate(request)).isSameAs(failure);
        verifyNoInteractions(fallback);
    }

    @ParameterizedTest
    @EnumSource(value = AiGenerationErrorType.class, names = {"RATE_LIMITED", "TIMEOUT"})
    void interruptedThreadDoesNotStartAnotherProvider(AiGenerationErrorType errorType) {
        var failure = new AiGenerationException(errorType, "primary failure");
        when(primary.generate(request)).thenThrow(failure);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> generator.generate(request)).isSameAs(failure);
            verifyNoInteractions(fallback);
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @EnumSource(value = AiGenerationErrorType.class, names = {"RATE_LIMITED", "TIMEOUT"})
    void fallbackFailurePreservesBothFailuresWithoutRetrying(AiGenerationErrorType errorType) {
        var first = new AiGenerationException(errorType, "Gemini failure");
        var second = new AiGenerationException(errorType, "Codex failure");
        when(primary.generate(request)).thenThrow(first);
        when(fallback.generate(request)).thenThrow(second);
        assertThatThrownBy(() -> generator.generate(request)).isSameAs(second).hasSuppressedException(first);
        verify(primary).generate(request);
        verify(fallback).generate(request);
    }
}
