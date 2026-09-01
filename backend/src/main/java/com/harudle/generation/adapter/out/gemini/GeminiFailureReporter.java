package com.harudle.generation.adapter.out.gemini;

import com.harudle.common.logging.ExternalApiFailure;
import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class GeminiFailureReporter {

    private static final String PROVIDER = "gemini";

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
            Exception exception
    ) {
        AiGenerationException translated = exceptionTranslator.translate(translationOperation, exception);
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
}
