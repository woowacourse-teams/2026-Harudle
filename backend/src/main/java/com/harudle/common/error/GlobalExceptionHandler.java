package com.harudle.common.error;

import com.harudle.auth.presentation.AuthenticationRequiredException;
import com.harudle.generation.service.exception.DailyGenerationLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ProblemDetailFactory problemDetailFactory;

    public GlobalExceptionHandler(ProblemDetailFactory problemDetailFactory) {
        this.problemDetailFactory = problemDetailFactory;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        List<FieldValidationError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldValidationError(error.getField(), error.getDefaultMessage()))
                .toList();
        return createResponse(ErrorType.VALIDATION_ERROR, request, errors);
    }

    @ExceptionHandler({
            HandlerMethodValidationException.class,
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ProblemDetail> handleValidation(
            Exception exception,
            HttpServletRequest request
    ) {
        return createResponse(ErrorType.VALIDATION_ERROR, request);
    }

    @ExceptionHandler(AuthenticationRequiredException.class)
    public ResponseEntity<ProblemDetail> handleAuthenticationRequired(
            AuthenticationRequiredException exception,
            HttpServletRequest request
    ) {
        return createResponse(ErrorType.UNAUTHORIZED, request);
    }

    @ExceptionHandler(DailyGenerationLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleDailyGenerationLimitExceeded(
            DailyGenerationLimitExceededException exception,
            HttpServletRequest request
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(exception.getRetryAfterSeconds()));
        ProblemDetail problemDetail = problemDetailFactory.create(
                ErrorType.DAILY_GENERATION_LIMIT_EXCEEDED,
                request
        );
        return new ResponseEntity<>(problemDetail, headers, HttpStatus.TOO_MANY_REQUESTS);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        if (exception instanceof ErrorResponse errorResponse) {
            return new ResponseEntity<>(
                    errorResponse.getBody(),
                    errorResponse.getHeaders(),
                    errorResponse.getStatusCode()
            );
        }
        LOGGER.error("예상하지 못한 API 오류가 발생했습니다.", exception);
        return createResponse(ErrorType.INTERNAL_SERVER_ERROR, request);
    }

    private ResponseEntity<ProblemDetail> createResponse(
            ErrorType errorType,
            HttpServletRequest request
    ) {
        return ResponseEntity
                .status(errorType.getStatus())
                .body(problemDetailFactory.create(errorType, request));
    }

    private ResponseEntity<ProblemDetail> createResponse(
            ErrorType errorType,
            HttpServletRequest request,
            List<FieldValidationError> errors
    ) {
        return ResponseEntity
                .status(errorType.getStatus())
                .body(problemDetailFactory.create(errorType, request, errors));
    }
}
