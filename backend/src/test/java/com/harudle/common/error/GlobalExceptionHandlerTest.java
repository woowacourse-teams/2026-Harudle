package com.harudle.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.exception.GenerationUnavailableException;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BindingResult;
import org.springframework.validation.DirectFieldBindingResult;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

class GlobalExceptionHandlerTest {

    private static final String TRACE_ID = "fixed-trace-id";

    private final ApiExceptionLogger apiExceptionLogger = mock(ApiExceptionLogger.class);
    private final GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler(
            new ProblemDetailFactory(new RequestTraceId(() -> TRACE_ID)),
            apiExceptionLogger
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
        verifyNoInteractions(apiExceptionLogger);
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
    @DisplayName("읽을 수 없는 요청 본문은 기존 검증 오류 메시지로 반환한다")
    void handleHttpMessageNotReadable() throws Exception {
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException(
                "Failed to read request",
                new MockHttpInputMessage(new byte[0])
        );

        ResponseEntity<Object> response = handleFrameworkException(exception);

        assertValidationError(response);
    }

    @Test
    @DisplayName("필수 요청 파라미터 누락은 기존 검증 오류 메시지로 반환한다")
    void handleMissingServletRequestParameter() throws Exception {
        MissingServletRequestParameterException exception =
                new MissingServletRequestParameterException("count", "integer");

        ResponseEntity<Object> response = handleFrameworkException(exception);

        assertValidationError(response);
    }

    @Test
    @DisplayName("요청 파라미터 타입 불일치는 기존 검증 오류 메시지로 반환한다")
    void handleMethodArgumentTypeMismatch() throws Exception {
        Method method = GlobalExceptionHandlerTest.class.getDeclaredMethod("validateCount", Integer.class);
        MethodArgumentTypeMismatchException exception = new MethodArgumentTypeMismatchException(
                "invalid-count",
                Integer.class,
                "count",
                new MethodParameter(method, 0),
                new NumberFormatException("숫자로 변환할 수 없습니다.")
        );

        ResponseEntity<Object> response = handleFrameworkException(exception);

        assertValidationError(response);
    }

    @Test
    @DisplayName("메서드 요청값 검증 오류는 기존 검증 오류 메시지로 반환한다")
    void handleHandlerMethodValidation() throws Exception {
        MethodValidationResult validationResult = mock(MethodValidationResult.class);
        when(validationResult.isForReturnValue()).thenReturn(false);
        HandlerMethodValidationException exception = new HandlerMethodValidationException(validationResult);

        ResponseEntity<Object> response = handleFrameworkException(exception);

        assertValidationError(response);
    }

    @Test
    @DisplayName("메서드 반환값 검증 오류는 내부 서버 오류 코드로 반환한다")
    void handleHandlerMethodReturnValueValidation() throws Exception {
        MethodValidationResult validationResult = mock(MethodValidationResult.class);
        when(validationResult.isForReturnValue()).thenReturn(true);
        HandlerMethodValidationException exception = new HandlerMethodValidationException(validationResult);

        ResponseEntity<Object> response = handleFrameworkException(exception);

        assertFrameworkError(response, 500, "INTERNAL_SERVER_ERROR");
        verify(apiExceptionLogger).error(response.getStatusCode(), exception, request);
    }

    @Test
    @DisplayName("응답 본문 작성 오류는 내부 서버 오류 코드로 반환한다")
    void handleHttpMessageNotWritable() throws Exception {
        HttpMessageNotWritableException exception = new HttpMessageNotWritableException(
                "응답 본문을 작성할 수 없습니다."
        );

        ResponseEntity<Object> response = handleFrameworkException(exception);

        assertFrameworkError(response, 500, "INTERNAL_SERVER_ERROR");
    }

    @Test
    @DisplayName("서버 내부 타입 변환 오류는 내부 서버 오류 코드로 반환한다")
    void handleConversionNotSupported() throws Exception {
        ConversionNotSupportedException exception = new ConversionNotSupportedException(
                "invalid-value",
                Integer.class,
                new IllegalStateException("지원하지 않는 타입 변환입니다.")
        );

        ResponseEntity<Object> response = handleFrameworkException(exception);

        assertFrameworkError(response, 500, "INTERNAL_SERVER_ERROR");
    }

    @Test
    @DisplayName("예상하지 못한 RuntimeException은 내부 서버 오류로 반환한다")
    void handleUnexpectedRuntimeException() {
        IllegalArgumentException exception = new IllegalArgumentException("서버 내부 불변식 오류");

        ResponseEntity<ProblemDetail> response = exceptionHandler.handleUnexpected(
                exception,
                request
        );

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties())
                .containsEntry("code", "INTERNAL_SERVER_ERROR")
                .containsEntry("traceId", TRACE_ID);
        verify(apiExceptionLogger).error(ErrorType.INTERNAL_SERVER_ERROR, exception, request);
    }

    @Test
    @DisplayName("생성 기능 필수 설정 누락은 서버 오류로 기록한다")
    void handleGenerationUnavailable() {
        GenerationUnavailableException exception = GenerationUnavailableException.adaptersNotConfigured();

        ResponseEntity<ProblemDetail> response = exceptionHandler.handleGenerationUnavailable(
                exception,
                request
        );

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties())
                .containsEntry("code", "GENERATION_UNAVAILABLE")
                .containsEntry("traceId", TRACE_ID);
        verify(apiExceptionLogger).error(ErrorType.GENERATION_UNAVAILABLE, exception, request);
    }

    @Test
    @DisplayName("외부 연동 예외는 전역 예외 처리기에서 중복 기록하지 않는다")
    void doNotLogTranslatedExternalFailure() {
        AiGenerationException exception = new AiGenerationException(
                AiGenerationErrorType.PROVIDER_ERROR,
                "Gemini 요청에 실패했습니다."
        );

        ResponseEntity<ProblemDetail> response = exceptionHandler.handleAiGeneration(
                exception,
                request
        );

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties())
                .containsEntry("code", "AI_PROVIDER_ERROR")
                .containsEntry("traceId", TRACE_ID);
        verifyNoInteractions(apiExceptionLogger);
    }

    private ResponseEntity<Object> handleFrameworkException(Exception exception) throws Exception {
        return exceptionHandler.handleException(exception, new ServletWebRequest(request));
    }

    private static void validate(ValidationRequest request) {
    }

    private static void validateCount(Integer count) {
    }

    private static void assertValidationError(ResponseEntity<Object> response) {
        assertFrameworkError(response, 400, "VALIDATION_ERROR");
        assertThat(response.getBody()).isInstanceOfSatisfying(ProblemDetail.class, problemDetail -> {
            assertThat(problemDetail.getTitle()).isEqualTo("Validation failed");
            assertThat(problemDetail.getDetail()).isEqualTo("요청 값이 올바르지 않습니다.");
        });
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
