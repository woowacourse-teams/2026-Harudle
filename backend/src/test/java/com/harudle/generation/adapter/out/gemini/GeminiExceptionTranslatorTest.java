package com.harudle.generation.adapter.out.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.genai.errors.ClientException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.errors.ServerException;
import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.exception.AiProviderFailureType;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GeminiExceptionTranslatorTest {

    private final GeminiExceptionTranslator translator = new GeminiExceptionTranslator();

    @ParameterizedTest
    @MethodSource("timeoutExceptions")
    @DisplayName("Gemini timeout 오류를 시간 초과 예외로 변환한다")
    void translateTimeoutException(RuntimeException cause) {
        AiGenerationException exception = translator.translate("스토리보드 생성", cause);

        assertThat(exception.errorType()).isEqualTo(AiGenerationErrorType.TIMEOUT);
        assertThat(exception).hasMessage("Gemini 스토리보드 생성 요청 시간이 초과되었습니다.");
        assertThat(exception).hasCause(cause);
    }

    @Test
    @DisplayName("Gemini의 일반 API 오류를 제공자 오류로 변환한다")
    void translateProviderException() {
        ServerException cause = new ServerException(500, "INTERNAL", "provider failure");

        AiGenerationException exception = translator.translate("스토리보드 생성", cause);

        assertThat(exception.errorType()).isEqualTo(AiGenerationErrorType.PROVIDER_ERROR);
        assertThat(exception).hasMessage("Gemini 스토리보드 생성 요청에 실패했습니다.");
        assertThat(exception).hasCause(cause);
    }

    @Test
    @DisplayName("Gemini의 429 오류를 요청 제한 오류로 변환한다")
    void translateRateLimitException() {
        ClientException cause = new ClientException(429, "RESOURCE_EXHAUSTED", "rate limited");

        AiGenerationException exception = translator.translate("스토리보드 생성", cause);

        assertThat(exception.errorType()).isEqualTo(AiGenerationErrorType.RATE_LIMITED);
        assertThat(exception).hasCause(cause);
    }

    @Test
    @DisplayName("구체적으로 분류되지 않은 예외는 기본 제공자 오류로 변환한다")
    void translateUnclassifiedException() {
        IllegalArgumentException cause = new IllegalArgumentException("unclassified failure");

        AiGenerationException exception = translator.translate("스토리보드 생성", cause);

        assertThat(exception.errorType()).isEqualTo(AiGenerationErrorType.PROVIDER_ERROR);
        assertThat(exception).hasCause(cause);
    }

    @ParameterizedTest
    @MethodSource("providerFailures")
    @DisplayName("광범위한 제공자 오류를 Service가 사용할 상세 유형으로 정규화한다")
    void normalizeProviderFailureDetails(Throwable cause, AiProviderFailureType expected) {
        AiGenerationException exception = translator.translate("스토리보드 생성", cause);

        assertThat(exception.errorType()).isEqualTo(AiGenerationErrorType.PROVIDER_ERROR);
        assertThat(exception.providerFailureType()).isEqualTo(expected);
        assertThat(exception).hasCause(cause);
    }

    @Test
    @DisplayName("스레드 인터럽트로 중단된 I/O는 정상 timeout과 구분하고 인터럽트 상태를 보존한다")
    void distinguishInterruptedIoFromProviderTimeout() {
        Thread.currentThread().interrupt();
        try {
            var cause = new GenAiIOException("interrupted", new InterruptedIOException("interrupted I/O"));
            var exception = translator.translate("스토리보드 생성", cause);

            assertThat(exception.errorType()).isEqualTo(AiGenerationErrorType.PROVIDER_ERROR);
            assertThat(exception.providerFailureType()).isEqualTo(AiProviderFailureType.CANCELLED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("응답 처리나 내부 오류로 명시한 예외를 원인만 보고 통신 오류로 바꾸지 않는다")
    void preserveExplicitProcessingErrorClassification() {
        var cause = new IOException("response processing failure");
        var exception = translator.translate("스토리보드 생성", cause, AiGenerationErrorType.RESPONSE_PROCESSING_ERROR);

        assertThat(exception.errorType()).isEqualTo(AiGenerationErrorType.RESPONSE_PROCESSING_ERROR);
        assertThat(exception.providerFailureType()).isEqualTo(AiProviderFailureType.UNKNOWN);
    }

    @Test
    @DisplayName("순환 원인 예외도 제한된 깊이에서 미분류 오류로 반환한다")
    void boundCyclicCauseTraversal() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second");
        first.initCause(second);
        second.initCause(first);

        var exception = translator.translate("스토리보드 생성", first);

        assertThat(exception.errorType()).isEqualTo(AiGenerationErrorType.PROVIDER_ERROR);
        assertThat(exception.providerFailureType()).isEqualTo(AiProviderFailureType.UNKNOWN);
    }

    private static Stream<Arguments> providerFailures() {
        return Stream.of(
                Arguments.of(new ClientException(400, "INVALID_ARGUMENT", "invalid input"),
                        AiProviderFailureType.REQUEST_REJECTED),
                Arguments.of(new ClientException(401, "UNAUTHENTICATED", "invalid key"),
                        AiProviderFailureType.AUTH_CONFIGURATION_ERROR),
                Arguments.of(new ClientException(403, "PERMISSION_DENIED", "permission denied"),
                        AiProviderFailureType.AUTH_CONFIGURATION_ERROR),
                Arguments.of(new ClientException(404, "NOT_FOUND", "missing model"),
                        AiProviderFailureType.REQUEST_REJECTED),
                Arguments.of(new ClientException(499, "CANCELLED", "cancelled"),
                        AiProviderFailureType.CANCELLED),
                Arguments.of(new ServerException(500, "INTERNAL", "internal error"),
                        AiProviderFailureType.TRANSIENT_SERVER_ERROR),
                Arguments.of(new ServerException(502, "BAD_GATEWAY", "gateway error"),
                        AiProviderFailureType.TRANSIENT_SERVER_ERROR),
                Arguments.of(new ServerException(503, "UNAVAILABLE", "unavailable"),
                        AiProviderFailureType.TRANSIENT_SERVER_ERROR),
                Arguments.of(new ServerException(501, "NOT_IMPLEMENTED", "unsupported operation"),
                        AiProviderFailureType.UNKNOWN),
                Arguments.of(new RuntimeException("wrapped API",
                        new ClientException(403, "PERMISSION_DENIED", "denied")),
                        AiProviderFailureType.AUTH_CONFIGURATION_ERROR),
                Arguments.of(new GenAiIOException("I/O failure", new IOException("connection reset")),
                        AiProviderFailureType.NETWORK_ERROR),
                Arguments.of(new CancellationException("cancelled"), AiProviderFailureType.CANCELLED),
                Arguments.of(new RuntimeException("interrupted", new InterruptedException()),
                        AiProviderFailureType.CANCELLED),
                Arguments.of(new IllegalArgumentException("unclassified"), AiProviderFailureType.UNKNOWN)
        );
    }

    private static Stream<Arguments> timeoutExceptions() {
        return Stream.of(
                Arguments.of(new ClientException(408, "REQUEST_TIMEOUT", "timeout")),
                Arguments.of(new ServerException(504, "GATEWAY_TIMEOUT", "timeout")),
                Arguments.of(new ClientException(400, "DEADLINE_EXCEEDED", "timeout")),
                Arguments.of(new GenAiIOException(
                        "I/O failure",
                        new SocketTimeoutException("read timeout")
                )),
                Arguments.of(new GenAiIOException("I/O failure", new InterruptedIOException("timeout"))),
                Arguments.of(new RuntimeException("wrapped timeout", new HttpTimeoutException("timeout")))
        );
    }
}
