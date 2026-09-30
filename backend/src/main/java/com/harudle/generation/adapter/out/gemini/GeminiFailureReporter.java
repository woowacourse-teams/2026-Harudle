package com.harudle.generation.adapter.out.gemini;

import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.harudle.common.logging.ExternalApiFailure;
import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.common.logging.ExternalApiResponseDiagnostics;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class GeminiFailureReporter {

    private static final String PROVIDER = "gemini";
    private static final String REQUEST_PREPARATION_ERROR = "REQUEST_PREPARATION_ERROR";

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
        String failureType = GeminiFailureType.provider(exception, translated.errorType()).name();
        externalApiLogger.warn(
                new ExternalApiFailure(
                        PROVIDER,
                        operation,
                        failureType,
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
        String classifiedFailureType = REQUEST_PREPARATION_ERROR.equals(failureType)
                ? GeminiFailureType.preparation(exception).name() : failureType;
        externalApiLogger.error(
                new ExternalApiFailure(
                        PROVIDER,
                        operation,
                        classifiedFailureType,
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
        AiGenerationException translated = exceptionTranslator.translate(translationOperation, exception);
        String failureType = GeminiFailureType.storyboardResponse(
                diagnostics.finishReason(), exception).name();
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

    AiGenerationException reportImageResponseFailure(
            String operation,
            String translationOperation,
            GenerateContentResponse response,
            Exception exception
    ) {
        AiGenerationException translated = exceptionTranslator.translate(translationOperation, exception);
        String failureType = GeminiFailureType.imageResponse(response).name();
        Integer candidateTokenCount = null;
        Integer thoughtTokenCount = null;
        try {
            Optional<GenerateContentResponseUsageMetadata> usage =
                    response == null ? Optional.empty() : response.usageMetadata();
            if (usage != null && usage.isPresent()) {
                candidateTokenCount = usage.get().candidatesTokenCount().orElse(null);
                thoughtTokenCount = usage.get().thoughtsTokenCount().orElse(null);
            }
        } catch (RuntimeException ignored) {
            // Response diagnostics must not replace the original response-processing failure.
        }
        ExternalApiResponseDiagnostics diagnostics = new ExternalApiResponseDiagnostics(
                GeminiFailureType.finishReason(response),
                candidateTokenCount,
                thoughtTokenCount,
                null,
                null
        );
        externalApiLogger.error(
                new ExternalApiFailure(PROVIDER, operation, failureType, null, null, null),
                exception,
                diagnostics
        );
        return translated;
    }
}
