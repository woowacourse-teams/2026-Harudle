package com.harudle.generation.adapter.out.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.genai.errors.ClientException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.errors.ServerException;
import com.harudle.generation.service.exception.AiGenerationErrorType;
import com.harudle.generation.service.exception.AiGenerationException;
import java.net.SocketTimeoutException;
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

        assertThat(exception.getErrorType()).isEqualTo(AiGenerationErrorType.TIMEOUT);
        assertThat(exception).hasMessage("Gemini 스토리보드 생성 요청 시간이 초과되었습니다.");
        assertThat(exception).hasCause(cause);
    }

    @Test
    @DisplayName("Gemini의 일반 API 오류를 제공자 오류로 변환한다")
    void translateProviderException() {
        ServerException cause = new ServerException(500, "INTERNAL", "provider failure");

        AiGenerationException exception = translator.translate("스토리보드 생성", cause);

        assertThat(exception.getErrorType()).isEqualTo(AiGenerationErrorType.PROVIDER_ERROR);
        assertThat(exception).hasMessage("Gemini 스토리보드 생성 요청에 실패했습니다.");
        assertThat(exception).hasCause(cause);
    }

    @Test
    @DisplayName("Gemini 응답 변환 오류를 제공자 오류로 변환한다")
    void translateResponseMappingException() {
        IllegalArgumentException cause = new IllegalArgumentException("invalid response");

        AiGenerationException exception = translator.translate("스토리보드 생성", cause);

        assertThat(exception.getErrorType()).isEqualTo(AiGenerationErrorType.PROVIDER_ERROR);
        assertThat(exception).hasCause(cause);
    }

    private static Stream<Arguments> timeoutExceptions() {
        return Stream.of(
                Arguments.of(new ClientException(408, "REQUEST_TIMEOUT", "timeout")),
                Arguments.of(new ServerException(504, "GATEWAY_TIMEOUT", "timeout")),
                Arguments.of(new ClientException(400, "DEADLINE_EXCEEDED", "timeout")),
                Arguments.of(new GenAiIOException(
                        "I/O failure",
                        new SocketTimeoutException("read timeout")
                ))
        );
    }
}
