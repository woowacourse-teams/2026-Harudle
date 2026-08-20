package com.harudle.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ExternalApiLoggerTest {

    private final ExternalApiLogger externalApiLogger = new ExternalApiLogger();

    @Test
    @DisplayName("외부 API 실패를 공통 필드와 정제된 스택트레이스로 기록한다")
    void logExternalApiFailureWithoutOriginalMessage(CapturedOutput output) {
        IllegalStateException exception = new IllegalStateException(
                "access_token=must-not-be-logged"
        );
        ExternalApiFailure failure = new ExternalApiFailure(
                "gemini",
                "storyboard_generation",
                "TIMEOUT",
                "DEADLINE_EXCEEDED",
                "504",
                null
        );

        externalApiLogger.warn(failure, exception);

        assertThat(output)
                .contains(" WARN ")
                .contains("event=external_api_failure")
                .contains("provider=gemini")
                .contains("operation=storyboard_generation")
                .contains("failureType=TIMEOUT")
                .contains("providerStatus=DEADLINE_EXCEEDED")
                .contains("providerCode=504")
                .contains("providerRequestId=none")
                .contains("exceptionType=IllegalStateException")
                .contains("java.lang.RuntimeException: 외부 연동 실패")
                .doesNotContain("must-not-be-logged");
    }

    @Test
    @DisplayName("안전하지 않은 외부 API 로그 필드는 전체를 치환한다")
    void replaceUnsafeFieldValue(CapturedOutput output) {
        ExternalApiFailure failure = new ExternalApiFailure(
                "gemini\nforged=value",
                "image_generation",
                "RESPONSE_PROCESSING_ERROR",
                null,
                null,
                null
        );

        externalApiLogger.error(failure, new IllegalArgumentException("응답 처리 실패"));

        assertThat(output)
                .contains(" ERROR ")
                .contains("provider=invalid")
                .doesNotContain("forged=value");
    }

    @Test
    @DisplayName("외부 연동 보상 실패는 별도 이벤트로 기록한다")
    void logCompensationFailure(CapturedOutput output) {
        ExternalApiFailure failure = new ExternalApiFailure(
                "s3",
                "delete_object",
                "CLIENT_ERROR",
                null,
                null,
                null
        );

        externalApiLogger.warnCompensation(failure, new IllegalStateException("delete failed"));

        assertThat(output)
                .contains(" WARN ")
                .contains("event=compensation_failure")
                .contains("provider=s3")
                .contains("operation=delete_object")
                .doesNotContain("delete failed");
    }
}
