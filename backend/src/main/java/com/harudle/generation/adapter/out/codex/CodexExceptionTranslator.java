package com.harudle.generation.adapter.out.codex;

import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.exception.AiProviderFailureType;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;

public final class CodexExceptionTranslator {

    private static final int MAX_CAUSE_DEPTH = 16;

    public AiGenerationException providerFailure(Throwable cause) {
        AiGenerationException classified = findClassified(cause);
        if (classified != null) {
            return classified;
        }
        if (Thread.currentThread().isInterrupted() || containsCancellation(cause)) {
            return create(AiGenerationErrorType.PROVIDER_ERROR, AiProviderFailureType.CANCELLED, cause);
        }
        Throwable providerCause = findProviderCause(cause);
        if (providerCause instanceof InterruptedIOException || providerCause instanceof HttpTimeoutException) {
            return create(AiGenerationErrorType.TIMEOUT, AiProviderFailureType.UNKNOWN, cause);
        }
        return create(AiGenerationErrorType.PROVIDER_ERROR,
                providerCause instanceof IOException ? AiProviderFailureType.NETWORK_ERROR : AiProviderFailureType.UNKNOWN,
                cause);
    }

    public AiGenerationException preparationFailure(Throwable cause) {
        return explicit(cause, AiGenerationErrorType.INTERNAL_ERROR);
    }

    public AiGenerationException responseFailure(Throwable cause) {
        return explicit(cause, AiGenerationErrorType.RESPONSE_PROCESSING_ERROR);
    }

    public AiGenerationException httpFailure(int status) {
        Throwable cause = new IllegalStateException("Codex worker HTTP " + status);
        return switch (status) {
            case 429 -> create(AiGenerationErrorType.RATE_LIMITED, AiProviderFailureType.UNKNOWN, cause);
            case 408, 504 -> create(AiGenerationErrorType.TIMEOUT, AiProviderFailureType.UNKNOWN, cause);
            case 401, 403 -> create(AiGenerationErrorType.PROVIDER_ERROR,
                    AiProviderFailureType.AUTH_CONFIGURATION_ERROR, cause);
            case 499 -> create(AiGenerationErrorType.PROVIDER_ERROR, AiProviderFailureType.CANCELLED, cause);
            case 500, 502, 503 -> create(AiGenerationErrorType.PROVIDER_ERROR,
                    AiProviderFailureType.TRANSIENT_SERVER_ERROR, cause);
            default -> create(AiGenerationErrorType.PROVIDER_ERROR,
                    status >= 400 && status < 500
                            ? AiProviderFailureType.REQUEST_REJECTED : AiProviderFailureType.UNKNOWN, cause);
        };
    }

    private static AiGenerationException explicit(Throwable cause, AiGenerationErrorType type) {
        AiGenerationException classified = findClassified(cause);
        return classified != null ? classified : create(type, AiProviderFailureType.UNKNOWN, cause);
    }

    private static AiGenerationException findClassified(Throwable cause) {
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++, cause = cause.getCause()) {
            if (cause instanceof AiGenerationException classified) {
                return classified;
            }
        }
        return null;
    }

    private static boolean containsCancellation(Throwable cause) {
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++, cause = cause.getCause()) {
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return true;
            }
            if (cause instanceof CancellationException) {
                return true;
            }
        }
        return false;
    }

    private static Throwable findProviderCause(Throwable cause) {
        Throwable ioFailure = null;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++, cause = cause.getCause()) {
            if (cause instanceof InterruptedIOException || cause instanceof HttpTimeoutException) {
                return cause;
            }
            if (cause instanceof IOException && ioFailure == null) {
                ioFailure = cause;
            }
        }
        return ioFailure;
    }

    private static AiGenerationException create(
            AiGenerationErrorType type, AiProviderFailureType detail, Throwable cause
    ) {
        String message = type == AiGenerationErrorType.TIMEOUT
                ? "Codex 이미지 생성 요청 시간이 초과되었습니다." : "Codex 이미지 생성 요청에 실패했습니다.";
        return new AiGenerationException(type, message, cause, detail);
    }
}
