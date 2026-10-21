package com.harudle.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.generation.diary.domain.GenerationErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ErrorTypeTest {

    @Test
    @DisplayName("enum 이름을 오류 코드와 problem type으로 사용한다")
    void deriveMachineReadableIdentifiers() {
        ErrorType errorType = ErrorType.DAILY_GENERATION_LIMIT_EXCEEDED;

        assertThat(errorType.code()).isEqualTo("DAILY_GENERATION_LIMIT_EXCEEDED");
        assertThat(errorType.problemType())
                .hasToString("urn:harudle:problem:daily-generation-limit-exceeded");
    }

    @Test
    @DisplayName("대기 만료 오류는 별도 오류 코드와 503으로 반환한다")
    void mapPendingGenerationTimeout() {
        ErrorType errorType = ErrorType.from(GenerationErrorCode.GENERATION_PENDING_TIMEOUT);

        assertThat(errorType).isEqualTo(ErrorType.GENERATION_PENDING_TIMEOUT);
        assertThat(errorType.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(errorType.code()).isEqualTo("GENERATION_PENDING_TIMEOUT");
        assertThat(errorType.problemType()).hasToString("urn:harudle:problem:generation-pending-timeout");
    }
}
