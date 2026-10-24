package com.harudle.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionLoggerTest {

    private static final String REQUEST_URI = "/api/v1/diaries/0d947550-009a-4c45-87e4-a829965533ef";
    private static final String PATH_PATTERN = "/api/v1/diaries/{diaryId}";

    private final ApiExceptionLogger apiExceptionLogger = new ApiExceptionLogger();
    private final MockHttpServletRequest request = new MockHttpServletRequest(
            HttpMethod.GET.name(),
            REQUEST_URI
    );

    @Test
    @DisplayName("API 내부 오류를 공통 필드와 라우트 패턴으로 기록한다")
    void logApiException(CapturedOutput output) {
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, PATH_PATTERN);
        IllegalStateException exception = new IllegalStateException("민감할 수 있는 내부 오류 상세");

        apiExceptionLogger.error(ErrorType.INTERNAL_SERVER_ERROR, exception, request);

        assertThat(output)
                .contains("event=api_exception")
                .contains("errorCode=INTERNAL_SERVER_ERROR")
                .contains("httpStatus=500")
                .contains("method=GET")
                .contains("path=" + PATH_PATTERN)
                .contains("exceptionType=IllegalStateException")
                .contains("java.lang.IllegalStateException: message omitted")
                .doesNotContain("민감할 수 있는 내부 오류 상세")
                .doesNotContain("path=" + REQUEST_URI);
    }

    @Test
    @DisplayName("원인과 억제된 예외의 유형·스택은 보존하고 메시지는 제거한다")
    void preservesSafeExceptionChain(CapturedOutput output) {
        IOException cause = new IOException("secret-s3-object-key");
        IllegalStateException exception = new IllegalStateException("secret-prompt", cause);
        exception.addSuppressed(new IllegalArgumentException("secret-request-url"));

        apiExceptionLogger.error(ErrorType.INTERNAL_SERVER_ERROR, exception, request);

        assertThat(output)
                .contains("java.lang.IllegalStateException: message omitted")
                .contains("Caused by: java.io.IOException: message omitted")
                .contains("Suppressed: java.lang.IllegalArgumentException: message omitted")
                .contains("ApiExceptionLoggerTest.java:")
                .doesNotContain("secret-s3-object-key", "secret-prompt", "secret-request-url");
    }

    @Test
    @DisplayName("라우트 패턴을 찾지 못하면 원본 URI를 로그에 남기지 않는다")
    void avoidsLoggingRawUriWithoutRoutePattern(CapturedOutput output) {
        apiExceptionLogger.error(ErrorType.INTERNAL_SERVER_ERROR,
                new IllegalStateException("서버 오류"), request);

        assertThat(output)
                .contains("path=UNMATCHED")
                .doesNotContain(REQUEST_URI);
    }
}
