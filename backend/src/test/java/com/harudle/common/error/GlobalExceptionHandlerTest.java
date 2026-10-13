package com.harudle.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harudle.auth.domain.policy.NicknamePolicy;
import com.harudle.category.service.exception.CategoryInactiveException;
import com.harudle.category.service.exception.CategoryNotFoundException;
import com.harudle.comment.service.exception.CommentIntegrationUnavailableException;
import com.harudle.generation.diary.domain.GenerationErrorCode;
import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.exception.DiaryGenerationFailedException;
import com.harudle.generation.diary.service.exception.GenerationUnavailableException;
import com.harudle.notification.service.exception.NotificationIntegrationUnavailableException;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BindingResult;
import org.springframework.validation.DirectFieldBindingResult;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
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

    @ParameterizedTest
    @CsvSource({"missing, 404, CATEGORY_NOT_FOUND", "inactive, 409, CATEGORY_INACTIVE"})
    @DisplayName("카테고리 조회와 게시 오류는 공통 오류 응답과 상태 코드로 전달한다")
    void returnsCategoryError(String error, int expectedStatus, String expectedCode) throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CategoryErrorController())
                .setControllerAdvice(exceptionHandler)
                .build();

        mockMvc.perform(get("/api/v1/test/category").param("error", error))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andExpect(jsonPath("$.detail").value(ErrorType.valueOf(expectedCode).detail()));
        verifyNoInteractions(apiExceptionLogger);
    }

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

    @ParameterizedTest
    @CsvSource({
            "' 신나는 하루', 닉네임 앞뒤의 공백을 제거해 주세요.",
            "'신나는 하루 ', 닉네임 앞뒤의 공백을 제거해 주세요.",
            "'신나는  하루', 닉네임에는 공백을 연속으로 사용할 수 없습니다.",
            "'신나는   하루', 닉네임에는 공백을 연속으로 사용할 수 없습니다.",
            "'하루🙂', '닉네임은 한글, 영문, 숫자와 공백만 사용할 수 있습니다.'",
            "'하루!', '닉네임은 한글, 영문, 숫자와 공백만 사용할 수 있습니다.'"
    })
    @DisplayName("닉네임 공백과 허용 문자 오류는 HTTP 400과 필드별 수정 안내로 전달한다")
    void returnsNicknameValidationReasonToClient(String nickname, String reason) throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new NicknameValidationController())
                .setControllerAdvice(exceptionHandler)
                .build();

        mockMvc.perform(get("/api/v1/test/nickname").param("nickname", nickname))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("nickname"))
                .andExpect(jsonPath("$.errors[0].reason").value(reason));
        verifyNoInteractions(apiExceptionLogger);
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
    @DisplayName("알림 프로필 연동 실패는 기존 503과 traceId를 보존하고 공통 오류 로그를 한 번 기록한다")
    void logsNotificationIntegrationUnavailable() {
        NotificationIntegrationUnavailableException exception = new NotificationIntegrationUnavailableException();

        ResponseEntity<ProblemDetail> response = exceptionHandler.handleNotificationUnavailable(exception, request);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo(ErrorType.NOTIFICATION_UNAVAILABLE.detail());
        assertThat(response.getBody().getProperties())
                .containsEntry("code", "NOTIFICATION_UNAVAILABLE")
                .containsEntry("traceId", TRACE_ID);
        verify(apiExceptionLogger).error(ErrorType.NOTIFICATION_UNAVAILABLE, exception, request);
        verifyNoMoreInteractions(apiExceptionLogger);
    }

    @Test
    @DisplayName("댓글 프로필 연동 실패도 기존 503과 traceId를 보존하고 공통 오류 로그를 한 번 기록한다")
    void logsCommentIntegrationUnavailable() {
        CommentIntegrationUnavailableException exception = new CommentIntegrationUnavailableException();

        ResponseEntity<ProblemDetail> response = exceptionHandler.handleCommentUnavailable(exception, request);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo(ErrorType.COMMENT_UNAVAILABLE.detail());
        assertThat(response.getBody().getProperties())
                .containsEntry("code", "COMMENT_UNAVAILABLE")
                .containsEntry("traceId", TRACE_ID);
        verify(apiExceptionLogger).error(ErrorType.COMMENT_UNAVAILABLE, exception, request);
        verifyNoMoreInteractions(apiExceptionLogger);
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

    @ParameterizedTest
    @EnumSource(value = AiGenerationErrorType.class, names = {
            "RATE_LIMITED", "OUTPUT_TRUNCATED", "RESPONSE_PROCESSING_ERROR"
    })
    @DisplayName("세부 AI 오류 분류는 사용자 API에서 기존 AI 제공자 오류로 반환한다")
    void returnExistingApiErrorForDetailedAiFailure(AiGenerationErrorType errorType) {
        AiGenerationException exception = new AiGenerationException(
                errorType,
                "Gemini 생성에 실패했습니다."
        );

        ResponseEntity<ProblemDetail> response = exceptionHandler.handleAiGeneration(exception, request);

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties()).containsEntry("code", "AI_PROVIDER_ERROR");
    }

    @ParameterizedTest
    @EnumSource(value = GenerationErrorCode.class, names = {
            "AI_PROVIDER_RATE_LIMITED", "AI_OUTPUT_TRUNCATED", "AI_RESPONSE_PROCESSING_ERROR"
    })
    @DisplayName("저장된 세부 AI 오류 코드도 사용자 API에서는 기존 오류로 반환한다")
    void returnExistingApiErrorForStoredAiFailure(GenerationErrorCode errorCode) {
        DiaryGenerationFailedException exception = new DiaryGenerationFailedException(
                errorCode
        );

        ResponseEntity<ProblemDetail> response = exceptionHandler.handleDiaryGenerationFailed(exception, request);

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties()).containsEntry("code", "AI_PROVIDER_ERROR");
    }

    @Test
    @DisplayName("예상하지 못한 내부 오류는 기존 내부 서버 오류로 반환한다")
    void returnInternalServerErrorForInternalAiFailure() {
        AiGenerationException exception = new AiGenerationException(
                AiGenerationErrorType.INTERNAL_ERROR,
                "Gemini 요청을 준비하지 못했습니다."
        );

        ResponseEntity<ProblemDetail> response = exceptionHandler.handleAiGeneration(exception, request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties()).containsEntry("code", "INTERNAL_SERVER_ERROR");
    }

    @Test
    @DisplayName("저장된 내부 생성 오류도 기존 내부 서버 오류로 반환한다")
    void returnInternalServerErrorForStoredInternalFailure() {
        DiaryGenerationFailedException exception = new DiaryGenerationFailedException(
                GenerationErrorCode.GENERATION_INTERNAL_ERROR
        );

        ResponseEntity<ProblemDetail> response = exceptionHandler.handleDiaryGenerationFailed(exception, request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties()).containsEntry("code", "INTERNAL_SERVER_ERROR");
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

    @RestController
    private static final class CategoryErrorController {

        @GetMapping("/api/v1/test/category")
        String categoryError(@RequestParam("error") String error) {
            if ("missing".equals(error)) {
                throw new CategoryNotFoundException();
            }
            throw new CategoryInactiveException();
        }
    }

    @RestController
    private static final class NicknameValidationController {

        @GetMapping("/api/v1/test/nickname")
        String validateNickname(@RequestParam("nickname") String nickname) {
            return NicknamePolicy.normalizeAndValidate(nickname);
        }
    }
}
