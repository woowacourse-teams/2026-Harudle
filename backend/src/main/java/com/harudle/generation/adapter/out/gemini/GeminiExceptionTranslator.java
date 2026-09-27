package com.harudle.generation.adapter.out.gemini;

import com.google.genai.errors.ApiException;
import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;

public final class GeminiExceptionTranslator {

    private static final int REQUEST_TIMEOUT_STATUS_CODE = 408;
    private static final int GATEWAY_TIMEOUT_STATUS_CODE = 504;
    private static final String DEADLINE_EXCEEDED_STATUS = "DEADLINE_EXCEEDED";

    public AiGenerationException translate(String operation, Throwable cause) {
        AiGenerationErrorType errorType = resolveErrorType(cause);
        String message = createMessage(operation, errorType);

        return new AiGenerationException(
                errorType,
                message,
                cause
        );
    }

    private static AiGenerationErrorType resolveErrorType(Throwable cause) {
        return switch (cause) {
            case ApiException apiException -> resolveApiErrorType(apiException);
            case SocketTimeoutException ignored -> AiGenerationErrorType.TIMEOUT;
            case HttpTimeoutException ignored -> AiGenerationErrorType.TIMEOUT;
            default -> resolveCauseErrorType(cause);
        };
    }

    private static AiGenerationErrorType resolveApiErrorType(ApiException exception) {
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

    private static AiGenerationErrorType resolveCauseErrorType(Throwable cause) {
        Throwable nestedCause = cause.getCause();
        if (nestedCause == null) {
            return AiGenerationErrorType.PROVIDER_ERROR;
        }
        return resolveErrorType(nestedCause);
    }

    private static String createMessage(String operation, AiGenerationErrorType errorType) {
        if (errorType == AiGenerationErrorType.TIMEOUT) {
            return "Gemini " + operation + " 요청 시간이 초과되었습니다.";
        }
        return "Gemini " + operation + " 요청에 실패했습니다.";
    }
}
