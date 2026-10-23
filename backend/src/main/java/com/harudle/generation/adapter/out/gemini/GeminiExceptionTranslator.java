package com.harudle.generation.adapter.out.gemini;

import com.google.genai.errors.ApiException;
import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.exception.AiProviderFailureType;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;

public final class GeminiExceptionTranslator {

    private static final int MAX_CAUSE_DEPTH = 16;
    private static final int REQUEST_TIMEOUT_STATUS_CODE = 408;
    private static final int GATEWAY_TIMEOUT_STATUS_CODE = 504;
    private static final int TOO_MANY_REQUESTS_STATUS_CODE = 429;
    private static final int CANCELLED_STATUS_CODE = 499;
    private static final String DEADLINE_EXCEEDED_STATUS = "DEADLINE_EXCEEDED";

    public AiGenerationException translate(String operation, Throwable cause) {
        Throwable providerCause = resolveProviderCause(cause);
        return createException(operation, cause, resolveErrorType(providerCause), providerCause);
    }

    AiGenerationException translate(String operation, Throwable cause, AiGenerationErrorType errorType) {
        return createException(operation, cause, errorType, resolveProviderCause(cause));
    }

    private static AiGenerationException createException(
            String operation,
            Throwable cause,
            AiGenerationErrorType errorType,
            Throwable providerCause
    ) {
        return new AiGenerationException(errorType, createMessage(operation, errorType), cause,
                resolveProviderFailureType(providerCause, errorType));
    }

    private static AiGenerationErrorType resolveErrorType(Throwable cause) {
        return switch (cause) {
            case ApiException apiException -> resolveApiErrorType(apiException);
            case SocketTimeoutException ignored -> AiGenerationErrorType.TIMEOUT;
            case HttpTimeoutException ignored -> AiGenerationErrorType.TIMEOUT;
            case InterruptedIOException ignored -> Thread.currentThread().isInterrupted()
                    ? AiGenerationErrorType.PROVIDER_ERROR : AiGenerationErrorType.TIMEOUT;
            case null, default -> AiGenerationErrorType.PROVIDER_ERROR;
        };
    }

    private static AiGenerationErrorType resolveApiErrorType(ApiException exception) {
        if (exception.code() == TOO_MANY_REQUESTS_STATUS_CODE) {
            return AiGenerationErrorType.RATE_LIMITED;
        }
        if (isTimeoutApiException(exception)) {
            return AiGenerationErrorType.TIMEOUT;
        }
        return AiGenerationErrorType.PROVIDER_ERROR;
    }

    private static boolean isTimeoutApiException(ApiException exception) {
        return exception.code() == REQUEST_TIMEOUT_STATUS_CODE
                || exception.code() == GATEWAY_TIMEOUT_STATUS_CODE
                || DEADLINE_EXCEEDED_STATUS.equals(exception.status());
    }

    private static AiProviderFailureType resolveProviderFailureType(Throwable cause, AiGenerationErrorType errorType) {
        if (isCancellation(cause)) {
            return AiProviderFailureType.CANCELLED;
        }
        if (errorType != AiGenerationErrorType.PROVIDER_ERROR) {
            return AiProviderFailureType.UNKNOWN;
        }
        if (cause instanceof ApiException apiException) {
            return resolveApiFailureType(apiException);
        }
        if (cause instanceof IOException) {
            return AiProviderFailureType.NETWORK_ERROR;
        }
        return AiProviderFailureType.UNKNOWN;
    }

    private static AiProviderFailureType resolveApiFailureType(ApiException exception) {
        int status = exception.code();
        if (status == 401 || status == 403) {
            return AiProviderFailureType.AUTH_CONFIGURATION_ERROR;
        }
        if (status == 500 || status == 502 || status == 503) {
            return AiProviderFailureType.TRANSIENT_SERVER_ERROR;
        }
        if (status >= 400 && status < 500) {
            return AiProviderFailureType.REQUEST_REJECTED;
        }
        return AiProviderFailureType.UNKNOWN;
    }

    private static boolean isCancellation(Throwable cause) {
        return cause instanceof CancellationException
                || cause instanceof InterruptedException
                || cause instanceof IOException && Thread.currentThread().isInterrupted()
                || cause instanceof ApiException apiException
                && (apiException.code() == CANCELLED_STATUS_CODE || "CANCELLED".equals(apiException.status()));
    }

    private static Throwable resolveProviderCause(Throwable exception) {
        Throwable current = exception;
        Throwable ioFailure = null;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (isClassifiedCause(current)) {
                return current;
            }
            if (current instanceof IOException && ioFailure == null) {
                ioFailure = current;
            }
            current = current.getCause();
        }
        return ioFailure == null ? exception : ioFailure;
    }

    private static boolean isClassifiedCause(Throwable cause) {
        return cause instanceof ApiException || cause instanceof HttpTimeoutException
                || cause instanceof InterruptedIOException || cause instanceof CancellationException
                || cause instanceof InterruptedException;
    }

    private static String createMessage(String operation, AiGenerationErrorType errorType) {
        if (errorType == AiGenerationErrorType.TIMEOUT) {
            return "Gemini " + operation + " 요청 시간이 초과되었습니다.";
        }
        return "Gemini " + operation + " 요청에 실패했습니다.";
    }
}
