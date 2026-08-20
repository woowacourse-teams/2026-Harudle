package com.harudle.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BindingResult;
import org.springframework.validation.DirectFieldBindingResult;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;

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
    void handleMethodNotAllowed() throws Exception {
        HttpRequestMethodNotSupportedException exception =
                new HttpRequestMethodNotSupportedException("POST", List.of("GET", "DELETE"));

        ResponseEntity<Object> response = handleFrameworkException(exception);

        assertFrameworkError(response, 405, "METHOD_NOT_ALLOWED");
        assertThat(response.getHeaders().getAllow())
                .containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.DELETE);
    }

    @Test
    @DisplayName("지원하지 않는 미디어 타입 오류에 공통 필드와 Accept 헤더를 보존한다")
    void handleUnsupportedMediaType() throws Exception {
        HttpMediaTypeNotSupportedException exception = new HttpMediaTypeNotSupportedException(
                MediaType.TEXT_PLAIN,
                List.of(MediaType.APPLICATION_JSON),
                HttpMethod.POST
        );

        ResponseEntity<Object> response = handleFrameworkException(exception);

        assertFrameworkError(response, 415, "UNSUPPORTED_MEDIA_TYPE");
        assertThat(response.getHeaders().getAccept()).containsExactly(MediaType.APPLICATION_JSON);
    }

    @Test
    @DisplayName("별도 코드가 없는 프레임워크 오류도 상태 기반 코드를 반환한다")
    void handleUnmappedFrameworkError() throws Exception {
        ErrorResponseException exception = new ErrorResponseException(HttpStatusCode.valueOf(422));

        ResponseEntity<Object> response = handleFrameworkException(exception);

        assertFrameworkError(response, 422, "HTTP_422");
    }

    @Test
    @DisplayName("요청 본문 검증 오류에 필드명과 검증 메시지를 포함한다")
    void handleMethodArgumentNotValid() throws Exception {
        BindingResult bindingResult = new DirectFieldBindingResult(
                new ValidationRequest(),
                "validationRequest"
        );
        bindingResult.rejectValue("content", "NotBlank", "비어 있을 수 없습니다.");
        Method method = GlobalExceptionHandlerTest.class.getDeclaredMethod(
                "validate",
                ValidationRequest.class
        );
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(
                new MethodParameter(method, 0),
                bindingResult
        );

        ResponseEntity<Object> response = handleFrameworkException(exception);

        assertFrameworkError(response, 400, "VALIDATION_ERROR");
        assertThat(response.getBody()).isInstanceOfSatisfying(ProblemDetail.class, problemDetail ->
                assertThat(problemDetail.getProperties())
                        .containsEntry("errors", List.of(new FieldValidationError(
                                "content",
                                "비어 있을 수 없습니다."
                        )))
        );
    }

    @Test
    @DisplayName("예상하지 못한 RuntimeException은 내부 서버 오류로 반환한다")
    void handleUnexpectedRuntimeException() {
        ResponseEntity<ProblemDetail> response = exceptionHandler.handleUnexpected(
                new IllegalArgumentException("서버 내부 불변식 오류"),
                request
        );

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties())
                .containsEntry("code", "INTERNAL_SERVER_ERROR")
                .containsEntry("traceId", TRACE_ID);
    }

    private ResponseEntity<Object> handleFrameworkException(Exception exception) throws Exception {
        return exceptionHandler.handleException(exception, new ServletWebRequest(request));
    }

    private static void validate(ValidationRequest request) {
    }

    private static void assertFrameworkError(
            ResponseEntity<Object> response,
            int expectedStatus,
            String expectedCode
    ) {
        assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
        assertThat(response.getBody()).isInstanceOfSatisfying(ProblemDetail.class, problemDetail -> {
            assertThat(problemDetail.getStatus()).isEqualTo(expectedStatus);
            assertThat(problemDetail.getType())
                    .hasToString("urn:harudle:problem:" + expectedCode.toLowerCase().replace('_', '-'));
            assertThat(problemDetail.getInstance()).hasToString("/api/v1/test");
            assertThat(problemDetail.getProperties())
                    .containsEntry("code", expectedCode)
                    .containsEntry("traceId", TRACE_ID);
        });
    }

    private static final class ValidationRequest {

        private String content;
    }
}
