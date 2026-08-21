package com.harudle.common.error;

import static org.assertj.core.api.Assertions.assertThat;

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
        IllegalStateException exception = new IllegalStateException("서버 내부 불변식 오류");

        apiExceptionLogger.error(ErrorType.INTERNAL_SERVER_ERROR, exception, request);

        assertThat(output)
                .contains("event=api_exception")
                .contains("errorCode=INTERNAL_SERVER_ERROR")
                .contains("httpStatus=500")
                .contains("method=GET")
                .contains("path=" + PATH_PATTERN)
                .contains("exceptionType=IllegalStateException")
                .contains("java.lang.IllegalStateException: 서버 내부 불변식 오류")
                .doesNotContain("path=" + REQUEST_URI);
    }
}
