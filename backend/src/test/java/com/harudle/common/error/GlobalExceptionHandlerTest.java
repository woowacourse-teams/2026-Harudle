package com.harudle.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

class GlobalExceptionHandlerTest {

    private static final String TRACE_ID = "fixed-trace-id";

    private final GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler(
            new ProblemDetailFactory(new RequestTraceId(() -> TRACE_ID))
    );
    private final MockHttpServletRequest request = new MockHttpServletRequest(
            HttpMethod.GET.name(),
            "/api/v1/test"
    );

    @Test
    @DisplayName("지원하지 않는 HTTP 메서드 오류에 공통 필드와 Allow 헤더를 보존한다")
    void handleMethodNotAllowed() {
        HttpRequestMethodNotSupportedException exception =
                new HttpRequestMethodNotSupportedException("POST", List.of("GET", "DELETE"));

        ResponseEntity<ProblemDetail> response = exceptionHandler.handleUnexpected(exception, request);

        assertFrameworkError(response, 405, "METHOD_NOT_ALLOWED");
        assertThat(response.getHeaders().getAllow())
                .containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.DELETE);
    }

    @Test
    @DisplayName("지원하지 않는 미디어 타입 오류에 공통 필드와 Accept 헤더를 보존한다")
    void handleUnsupportedMediaType() {
        HttpMediaTypeNotSupportedException exception = new HttpMediaTypeNotSupportedException(
                MediaType.TEXT_PLAIN,
                List.of(MediaType.APPLICATION_JSON),
                HttpMethod.POST
        );

        ResponseEntity<ProblemDetail> response = exceptionHandler.handleUnexpected(exception, request);

        assertFrameworkError(response, 415, "UNSUPPORTED_MEDIA_TYPE");
        assertThat(response.getHeaders().getAccept()).containsExactly(MediaType.APPLICATION_JSON);
    }

    @Test
    @DisplayName("별도 코드가 없는 프레임워크 오류도 상태 기반 코드를 반환한다")
    void handleUnmappedFrameworkError() {
        ErrorResponseException exception = new ErrorResponseException(HttpStatusCode.valueOf(422));

        ResponseEntity<ProblemDetail> response = exceptionHandler.handleUnexpected(exception, request);

        assertFrameworkError(response, 422, "HTTP_422");
    }

    private static void assertFrameworkError(
            ResponseEntity<ProblemDetail> response,
            int expectedStatus,
            String expectedCode
    ) {
        ProblemDetail problemDetail = response.getBody();

        assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(expectedStatus);
        assertThat(problemDetail.getType())
                .hasToString("urn:harudle:problem:" + expectedCode.toLowerCase().replace('_', '-'));
        assertThat(problemDetail.getInstance()).hasToString("/api/v1/test");
        assertThat(problemDetail.getProperties())
                .containsEntry("code", expectedCode)
                .containsEntry("traceId", TRACE_ID);
    }
}
