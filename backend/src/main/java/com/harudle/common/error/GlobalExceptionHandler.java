package com.harudle.common.error;

import com.harudle.auth.presentation.AuthenticationRequiredException;
import com.harudle.diary.presentation.InvalidIdempotencyKeyException;
import com.harudle.diary.service.exception.DiaryAccessDeniedException;
import com.harudle.diary.service.exception.DiaryNotFoundException;
import com.harudle.generation.service.exception.AiGenerationException;
import com.harudle.generation.service.exception.DiaryGenerationFailedException;
import com.harudle.generation.service.exception.DailyGenerationLimitExceededException;
import com.harudle.generation.service.exception.GenerationInProgressException;
import com.harudle.generation.service.exception.GenerationUnavailableException;
import com.harudle.generation.service.exception.IdempotencyKeyConflictException;
import com.harudle.generation.service.port.ImageStorageException;
import com.harudle.guest.application.exception.GuestSessionExpiredException;
import com.harudle.guest.application.exception.GuestSessionRequiredException;
import com.harudle.guest.application.exception.GuestTrialAlreadyUsedException;
import com.harudle.share.service.exception.ShareGenerationFailedException;
import com.harudle.share.service.exception.ShareNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ProblemDetailFactory problemDetailFactory;

    GlobalExceptionHandler(ProblemDetailFactory problemDetailFactory) {
        this.problemDetailFactory = problemDetailFactory;
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
        return createResponse(ErrorType.UNAUTHORIZED, request, headers);
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
    ResponseEntity<ProblemDetail> handleGenerationUnavailable(HttpServletRequest request) {
        return createResponse(ErrorType.GENERATION_UNAVAILABLE, request);
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<ProblemDetail> handleUnexpected(
            RuntimeException exception,
            HttpServletRequest request
    ) {
        LOGGER.error("예상하지 못한 API 오류가 발생했습니다.", exception);
        return createResponse(ErrorType.INTERNAL_SERVER_ERROR, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request
    ) {
        if (statusCode.is5xxServerError()) {
            LOGGER.error("프레임워크 API 오류가 발생했습니다.", exception);
        }
        return super.handleExceptionInternal(exception, body, headers, statusCode, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request
    ) {
        ProblemDetail problemDetail = ProblemDetail.class.cast(
                Objects.requireNonNull(body, "Spring MVC 오류 응답 본문은 필수입니다.")
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

    private ResponseEntity<ProblemDetail> createResponse(
            ErrorType errorType,
            HttpServletRequest request,
            HttpHeaders headers
    ) {
        return ResponseEntity
                .status(errorType.status())
                .headers(headers)
                .body(problemDetailFactory.create(errorType, request));
    }

    private static HttpServletRequest extractRequest(WebRequest request) {
        return ServletWebRequest.class.cast(request).getRequest();
    }
}
