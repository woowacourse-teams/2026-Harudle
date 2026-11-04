package com.harudle.generation.adapter.out.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.exception.AiProviderFailureType;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class OpenAiExceptionTranslatorTest {

    private final OpenAiExceptionTranslator translator = new OpenAiExceptionTranslator();

    @ParameterizedTest
    @CsvSource({
            "429,RATE_LIMITED,UNKNOWN", "408,TIMEOUT,UNKNOWN", "504,TIMEOUT,UNKNOWN",
            "401,PROVIDER_ERROR,AUTH_CONFIGURATION_ERROR", "403,PROVIDER_ERROR,AUTH_CONFIGURATION_ERROR",
            "400,PROVIDER_ERROR,REQUEST_REJECTED", "404,PROVIDER_ERROR,REQUEST_REJECTED",
            "413,PROVIDER_ERROR,REQUEST_REJECTED", "422,PROVIDER_ERROR,REQUEST_REJECTED",
            "499,PROVIDER_ERROR,CANCELLED", "500,PROVIDER_ERROR,TRANSIENT_SERVER_ERROR",
            "502,PROVIDER_ERROR,TRANSIENT_SERVER_ERROR", "503,PROVIDER_ERROR,TRANSIENT_SERVER_ERROR",
            "501,PROVIDER_ERROR,UNKNOWN", "302,PROVIDER_ERROR,UNKNOWN"
    })
    void mapsHttpFailuresLikeGemini(int status, AiGenerationErrorType type, AiProviderFailureType detail) {
        var failure = translator.httpFailure(status);
        assertThat(failure.errorType()).isEqualTo(type);
        assertThat(failure.providerFailureType()).isEqualTo(detail);
    }

    @ParameterizedTest
    @MethodSource("providerFailures")
    void mapsWrappedTransportFailures(Throwable cause, AiGenerationErrorType type, AiProviderFailureType detail) {
        var failure = translator.providerFailure(cause);
        assertThat(failure.errorType()).isEqualTo(type);
        assertThat(failure.providerFailureType()).isEqualTo(detail);
        assertThat(failure).hasCause(cause);
    }

    static Stream<Arguments> providerFailures() {
        return Stream.of(
                Arguments.of(new SocketTimeoutException(), AiGenerationErrorType.TIMEOUT, AiProviderFailureType.UNKNOWN),
                Arguments.of(new HttpTimeoutException("timeout"), AiGenerationErrorType.TIMEOUT, AiProviderFailureType.UNKNOWN),
                Arguments.of(new InterruptedIOException(), AiGenerationErrorType.TIMEOUT, AiProviderFailureType.UNKNOWN),
                Arguments.of(new IOException("reset"), AiGenerationErrorType.PROVIDER_ERROR, AiProviderFailureType.NETWORK_ERROR),
                Arguments.of(new IllegalStateException("wrapped", new SocketTimeoutException()),
                        AiGenerationErrorType.TIMEOUT, AiProviderFailureType.UNKNOWN),
                Arguments.of(new IllegalStateException("wrapped", new IOException("reset")),
                        AiGenerationErrorType.PROVIDER_ERROR, AiProviderFailureType.NETWORK_ERROR),
                Arguments.of(new IllegalStateException("provider failure"),
                        AiGenerationErrorType.PROVIDER_ERROR, AiProviderFailureType.UNKNOWN),
                Arguments.of(new CancellationException(), AiGenerationErrorType.PROVIDER_ERROR, AiProviderFailureType.CANCELLED)
        );
    }

    @Test
    void separatesLocalPreparationAndResponseProcessingFromProviderFailure() {
        var cause = new IllegalStateException("unexpected error");
        assertThat(translator.preparationFailure(cause).errorType()).isEqualTo(AiGenerationErrorType.INTERNAL_ERROR);
        assertThat(translator.responseFailure(cause).errorType()).isEqualTo(AiGenerationErrorType.RESPONSE_PROCESSING_ERROR);
        assertThat(translator.providerFailure(cause).errorType()).isEqualTo(AiGenerationErrorType.PROVIDER_ERROR);
    }

    @Test
    void responseParsingDiagnosticsNeverExposeResponseContent() {
        var failure = translator.responseFailure(new IllegalArgumentException("private-base64-or-prompt"));
        assertThat(failure).hasMessageNotContaining("private-base64-or-prompt");
        assertThat(failure.getCause()).hasMessageNotContaining("private-base64-or-prompt");
        assertThat(failure.getCause().getCause()).isNull();
    }

    @Test
    void preservesTheSameClassifiedFailureAtEveryBoundary() {
        var original = new AiGenerationException(AiGenerationErrorType.RATE_LIMITED, "classified");
        var wrapped = new IllegalStateException("wrapped", original);
        for (Throwable cause : new Throwable[]{original, wrapped}) {
            assertThat(translator.preparationFailure(cause)).isSameAs(original);
            assertThat(translator.providerFailure(cause)).isSameAs(original);
            assertThat(translator.responseFailure(cause)).isSameAs(original);
        }
    }

    @Test
    void interruptedIoIsCancellationAndPreservesTheInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            var failure = translator.providerFailure(new InterruptedIOException());
            assertThat(failure.providerFailureType()).isEqualTo(AiProviderFailureType.CANCELLED);
            assertThat(failure.errorType()).isEqualTo(AiGenerationErrorType.PROVIDER_ERROR);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void wrappedInterruptedExceptionRestoresTheInterruptFlag() {
        try {
            var failure = translator.providerFailure(new IllegalStateException("wrapped", new InterruptedException()));
            assertThat(failure.providerFailureType()).isEqualTo(AiProviderFailureType.CANCELLED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void cyclicCausesAreBounded() {
        var first = new RuntimeException();
        var second = new RuntimeException(first);
        first.initCause(second);
        assertThat(translator.providerFailure(first).providerFailureType()).isEqualTo(AiProviderFailureType.UNKNOWN);
    }
}
