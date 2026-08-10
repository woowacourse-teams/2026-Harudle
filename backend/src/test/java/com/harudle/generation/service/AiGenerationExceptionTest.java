package com.harudle.generation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiGenerationExceptionTest {

    @Test
    @DisplayName("AI 생성 오류 타입과 원인을 보존한다")
    void createAiGenerationException() {
        RuntimeException cause = new RuntimeException("provider failure");

        assertThatThrownBy(() -> {
            throw new AiGenerationException(
                    AiGenerationErrorType.PROVIDER_ERROR,
                    "AI 제공자 호출에 실패했습니다.",
                    cause
            );
        }).isInstanceOfSatisfying(
                AiGenerationException.class,
                exception -> {
                    assertThat(exception.getErrorType()).isEqualTo(AiGenerationErrorType.PROVIDER_ERROR);
                    assertThat(exception).hasMessage("AI 제공자 호출에 실패했습니다.");
                    assertThat(exception).hasCause(cause);
                }
        );
    }

    @Test
    @DisplayName("원인 예외 없이 AI 생성 시간 초과 오류를 생성한다")
    void createAiGenerationExceptionWithoutCause() {
        assertThatThrownBy(() -> {
            throw new AiGenerationException(
                    AiGenerationErrorType.TIMEOUT,
                    "AI 생성 시간이 초과되었습니다."
            );
        }).isInstanceOfSatisfying(
                AiGenerationException.class,
                exception -> {
                    assertThat(exception.getErrorType()).isEqualTo(AiGenerationErrorType.TIMEOUT);
                    assertThat(exception).hasMessage("AI 생성 시간이 초과되었습니다.");
                    assertThat(exception.getCause()).isNull();
                }
        );
    }

    @Test
    @DisplayName("AI 생성 오류 타입이 없으면 예외를 생성할 수 없다")
    void rejectNullErrorType() {
        assertThatThrownBy(() -> {
            throw new AiGenerationException(null, "AI 생성에 실패했습니다.");
        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("오류 타입");
    }

    @Test
    @DisplayName("AI 생성 오류 메시지가 비어 있으면 예외를 생성할 수 없다")
    void rejectBlankMessage() {
        assertThatThrownBy(() -> {
            throw new AiGenerationException(AiGenerationErrorType.PROVIDER_ERROR, " ");
        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("오류 메시지");
    }
}
