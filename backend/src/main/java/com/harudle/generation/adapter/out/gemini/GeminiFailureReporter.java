package com.harudle.generation.adapter.out.gemini;

import com.harudle.common.logging.ExternalApiFailure;
import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.common.logging.ExternalApiResponseDiagnostics;
import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class GeminiFailureReporter {

    private static final String PROVIDER = "gemini";
    private static final String MAX_TOKENS = "MAX_TOKENS";
    private static final String OUTPUT_TRUNCATED = "OUTPUT_TRUNCATED";
    private static final String RESPONSE_PROCESSING_ERROR = "RESPONSE_PROCESSING_ERROR";

    private final GeminiExceptionTranslator exceptionTranslator;
    private final ExternalApiLogger externalApiLogger;

    public GeminiFailureReporter(
            GeminiExceptionTranslator exceptionTranslator,
            ExternalApiLogger externalApiLogger
    ) {
        this.exceptionTranslator = exceptionTranslator;
        this.externalApiLogger = externalApiLogger;
    }

    AiGenerationException reportProviderFailure(
            String operation,
            String translationOperation,
            Exception exception
    ) {
        AiGenerationException translated = exceptionTranslator.translate(translationOperation, exception);
        GeminiProviderErrorMetadata metadata = GeminiProviderErrorMetadata.from(exception);
        externalApiLogger.warn(
                new ExternalApiFailure(
                        PROVIDER,
                        operation,
                        translated.errorType().name(),
                        metadata.status(),
                        metadata.code(),
                        null
                ),
                exception
        );
        return translated;
    }

    AiGenerationException reportInternalFailure(
            String operation,
            String translationOperation,
            String failureType,
            AiGenerationErrorType errorType,
            Exception exception
    ) {
        AiGenerationException translated = exceptionTranslator.translate(
                translationOperation,
                exception,
                errorType
        );
        externalApiLogger.error(
                new ExternalApiFailure(
                        PROVIDER,
                        operation,
                        failureType,
                        null,
                        null,
                        null
                ),
                exception
        );
        return translated;
    }

    AiGenerationException reportStoryboardResponseFailure(
            String operation,
            String translationOperation,
            ExternalApiResponseDiagnostics diagnostics,
            Exception exception
    ) {
        boolean outputTruncated = MAX_TOKENS.equals(diagnostics.finishReason());
        String failureType = outputTruncated ? OUTPUT_TRUNCATED : RESPONSE_PROCESSING_ERROR;
        AiGenerationErrorType errorType = outputTruncated
                ? AiGenerationErrorType.OUTPUT_TRUNCATED
                : AiGenerationErrorType.RESPONSE_PROCESSING_ERROR;
        AiGenerationException translated = exceptionTranslator.translate(
                translationOperation,
                exception,
                errorType
        );
        externalApiLogger.error(
                new ExternalApiFailure(
                        PROVIDER,
                        operation,
                        failureType,
                        null,
                        null,
                        null
                ),
                exception,
                diagnostics
        );
        return translated;
    }
}
