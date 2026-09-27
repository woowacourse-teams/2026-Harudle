package com.harudle.common.error;

import com.harudle.admin.service.exception.AdminUserNotFoundException;
import com.harudle.admin.service.exception.AdminGenerationLimitBelowUsageException;
import com.harudle.admin.service.exception.AdminGenerationUsageConflictException;
import com.harudle.admin.service.exception.AdminGenerationHistoryDateRangeException;
import com.harudle.admin.service.exception.AdminInactiveUserException;
import com.harudle.auth.presentation.AuthenticationRequiredException;
import com.harudle.common.validation.InvalidIdempotencyKeyException;
import com.harudle.diary.service.exception.DiaryAccessDeniedException;
import com.harudle.diary.service.exception.DiaryNotFoundException;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.exception.DiaryGenerationFailedException;
import com.harudle.generation.usage.service.exception.DailyGenerationLimitExceededException;
import com.harudle.generation.diary.service.exception.GenerationInProgressException;
import com.harudle.generation.diary.service.exception.GenerationUnavailableException;
import com.harudle.generation.diary.service.exception.IdempotencyKeyConflictException;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.guest.application.exception.GuestSessionExpiredException;
import com.harudle.guest.application.exception.GuestSessionRequiredException;
import com.harudle.guest.application.exception.GuestTrialAlreadyUsedException;
import com.harudle.share.service.exception.ShareGenerationFailedException;
import com.harudle.share.service.exception.ShareNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
@NullMarked
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private final ProblemDetailFactory problemDetailFactory;
    private final ApiExceptionLogger apiExceptionLogger;

    GlobalExceptionHandler(
            ProblemDetailFactory problemDetailFactory,
            ApiExceptionLogger apiExceptionLogger
    ) {
        this.problemDetailFactory = problemDetailFactory;
        this.apiExceptionLogger = apiExceptionLogger;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        ProblemDetail problemDetail = problemDetailFactory.create(
                ErrorType.VALIDATION_ERROR,
                extractRequest(request),
                FieldValidationErrorMapper.from(exception.getBindingResult())
        );
        return new ResponseEntity<>(problemDetail, headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        return createValidationResponse(headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        return createValidationResponse(headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        return createValidationResponse(headers, status, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        if (exception.isForReturnValue()) {
            return super.handleHandlerMethodValidationException(exception, headers, status, request);
        }
        return createValidationResponse(headers, status, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleValidation(HttpServletRequest request) {
        return createResponse(ErrorType.VALIDATION_ERROR, request);
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    ResponseEntity<ProblemDetail> handleInvalidIdempotencyKey(HttpServletRequest request) {
        return createResponse(ErrorType.INVALID_IDEMPOTENCY_KEY, request);
    }

    @ExceptionHandler(AuthenticationRequiredException.class)
    ResponseEntity<ProblemDetail> handleAuthenticationRequired(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return ResponseEntity
                .status(ErrorType.UNAUTHORIZED.status())
                .headers(headers)
                .body(problemDetailFactory.create(ErrorType.UNAUTHORIZED, request));
    }

    @ExceptionHandler(GuestSessionRequiredException.class)
    ResponseEntity<ProblemDetail> handleGuestSessionRequired(HttpServletRequest request) {
        return createResponse(ErrorType.GUEST_SESSION_REQUIRED, request);
    }

    @ExceptionHandler(GuestSessionExpiredException.class)
    ResponseEntity<ProblemDetail> handleGuestSessionExpired(HttpServletRequest request) {
        return createResponse(ErrorType.GUEST_SESSION_EXPIRED, request);
    }

    @ExceptionHandler(DiaryAccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleDiaryAccessDenied(HttpServletRequest request) {
        return createResponse(ErrorType.FORBIDDEN, request);
    }

    @ExceptionHandler(DiaryNotFoundException.class)
    ResponseEntity<ProblemDetail> handleDiaryNotFound(HttpServletRequest request) {
        return createResponse(ErrorType.DIARY_NOT_FOUND, request);
    }

    @ExceptionHandler(ShareNotFoundException.class)
    ResponseEntity<ProblemDetail> handleShareNotFound(HttpServletRequest request) {
        return createResponse(ErrorType.SHARE_NOT_FOUND, request);
    }

    @ExceptionHandler(AdminUserNotFoundException.class)
    ResponseEntity<ProblemDetail> handleAdminUserNotFound(HttpServletRequest request) {
        return createResponse(ErrorType.USER_NOT_FOUND, request);
    }

    @ExceptionHandler(AdminInactiveUserException.class)
    ResponseEntity<ProblemDetail> handleAdminInactiveUser(HttpServletRequest request) {
        return createResponse(ErrorType.INACTIVE_USER, request);
    }

    @ExceptionHandler(AdminGenerationUsageConflictException.class)
    ResponseEntity<ProblemDetail> handleAdminGenerationUsageConflict(HttpServletRequest request) {
        return createResponse(ErrorType.GENERATION_USAGE_CONFLICT, request);
    }

    @ExceptionHandler(AdminGenerationLimitBelowUsageException.class)
    ResponseEntity<ProblemDetail> handleAdminGenerationLimitBelowUsage(HttpServletRequest request) {
        return createResponse(ErrorType.GENERATION_LIMIT_BELOW_USAGE, request);
    }

    @ExceptionHandler(AdminGenerationHistoryDateRangeException.class)
    ResponseEntity<ProblemDetail> handleAdminGenerationHistoryDateRange(HttpServletRequest request) {
        return createResponse(ErrorType.VALIDATION_ERROR, request);
    }

    @ExceptionHandler(GenerationInProgressException.class)
    ResponseEntity<ProblemDetail> handleGenerationInProgress(HttpServletRequest request) {
        return createResponse(ErrorType.GENERATION_IN_PROGRESS, request);
    }

    @ExceptionHandler(ShareGenerationFailedException.class)
    ResponseEntity<ProblemDetail> handleShareGenerationFailed(HttpServletRequest request) {
        return createResponse(ErrorType.GENERATION_FAILED, request);
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    ResponseEntity<ProblemDetail> handleIdempotencyKeyConflict(HttpServletRequest request) {
        return createResponse(ErrorType.IDEMPOTENCY_KEY_CONFLICT, request);
    }

    @ExceptionHandler(GuestTrialAlreadyUsedException.class)
    ResponseEntity<ProblemDetail> handleGuestTrialAlreadyUsed(HttpServletRequest request) {
        return createResponse(ErrorType.GUEST_TRIAL_ALREADY_USED, request);
    }

    @ExceptionHandler(DailyGenerationLimitExceededException.class)
    ResponseEntity<ProblemDetail> handleDailyGenerationLimitExceeded(
            DailyGenerationLimitExceededException exception,
            HttpServletRequest request
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()));
        ProblemDetail problemDetail = problemDetailFactory.create(
                ErrorType.DAILY_GENERATION_LIMIT_EXCEEDED,
                request
        );
        return new ResponseEntity<>(problemDetail, headers, HttpStatus.TOO_MANY_REQUESTS);
    }

    @ExceptionHandler(DiaryGenerationFailedException.class)
    ResponseEntity<ProblemDetail> handleDiaryGenerationFailed(
            DiaryGenerationFailedException exception,
            HttpServletRequest request
    ) {
        return createResponse(ErrorType.from(exception.errorCode()), request);
    }

    @ExceptionHandler(AiGenerationException.class)
    ResponseEntity<ProblemDetail> handleAiGeneration(
            AiGenerationException exception,
            HttpServletRequest request
    ) {
        return createResponse(ErrorType.from(exception.errorType()), request);
    }

    @ExceptionHandler(ImageStorageException.class)
    ResponseEntity<ProblemDetail> handleImageStorage(HttpServletRequest request) {
        return createResponse(ErrorType.IMAGE_STORAGE_ERROR, request);
    }

    @ExceptionHandler(GenerationUnavailableException.class)
    ResponseEntity<ProblemDetail> handleGenerationUnavailable(
            GenerationUnavailableException exception,
            HttpServletRequest request
    ) {
        apiExceptionLogger.error(ErrorType.GENERATION_UNAVAILABLE, exception, request);
        return createResponse(ErrorType.GENERATION_UNAVAILABLE, request);
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<ProblemDetail> handleUnexpected(
            RuntimeException exception,
            HttpServletRequest request
    ) {
        apiExceptionLogger.error(ErrorType.INTERNAL_SERVER_ERROR, exception, request);
        return createResponse(ErrorType.INTERNAL_SERVER_ERROR, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            @Nullable Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request
    ) {
        if (statusCode.is5xxServerError()) {
            apiExceptionLogger.error(statusCode, exception, extractRequest(request));
        }
        return super.handleExceptionInternal(exception, body, headers, statusCode, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request
    ) {
        ProblemDetail problemDetail = (ProblemDetail) Objects.requireNonNull(
                body,
                "Spring MVC 오류 응답 본문은 필수입니다."
        );
        return new ResponseEntity<>(
                problemDetailFactory.enrichFrameworkError(
                        problemDetail,
                        statusCode,
                        extractRequest(request)
                ),
                headers,
                statusCode
        );
    }

    private ResponseEntity<ProblemDetail> createResponse(
            ErrorType errorType,
            HttpServletRequest request
    ) {
        return createResponse(errorType, request, List.of());
    }

    private ResponseEntity<ProblemDetail> createResponse(
            ErrorType errorType,
            HttpServletRequest request,
            List<FieldValidationError> errors
    ) {
        return ResponseEntity
                .status(errorType.status())
                .body(problemDetailFactory.create(errorType, request, errors));
    }

    private ResponseEntity<Object> createValidationResponse(
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        return new ResponseEntity<>(
                problemDetailFactory.create(
                        ErrorType.VALIDATION_ERROR,
                        extractRequest(request)
                ),
                headers,
                status
        );
    }

    private static HttpServletRequest extractRequest(WebRequest request) {
        return ((ServletWebRequest) request).getRequest();
    }
}
