package com.harudle.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ErrorTypeTest {

    @Test
    @DisplayName("enum 이름을 오류 코드와 problem type으로 사용한다")
    void deriveMachineReadableIdentifiers() {
        ErrorType errorType = ErrorType.DAILY_GENERATION_LIMIT_EXCEEDED;

        assertThat(errorType.code()).isEqualTo("DAILY_GENERATION_LIMIT_EXCEEDED");
        assertThat(errorType.problemType())
                .hasToString("urn:harudle:problem:daily-generation-limit-exceeded");
    }
}
