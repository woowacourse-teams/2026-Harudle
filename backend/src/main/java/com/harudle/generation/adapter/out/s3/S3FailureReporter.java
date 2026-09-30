package com.harudle.generation.adapter.out.s3;

import com.harudle.common.logging.ExternalApiFailure;
import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.diary.service.port.ImageStorageException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class S3FailureReporter {

    private static final String PROVIDER = "s3";
    private static final String REQUEST_VALIDATION_ERROR = "REQUEST_VALIDATION_ERROR";
    private static final int MAX_CAUSE_DEPTH = 16;

    private final S3ExceptionTranslator exceptionTranslator;
    private final ExternalApiLogger externalApiLogger;

    public S3FailureReporter(
            S3ExceptionTranslator exceptionTranslator,
            ExternalApiLogger externalApiLogger
    ) {
        this.exceptionTranslator = exceptionTranslator;
        this.externalApiLogger = externalApiLogger;
    }

    ImageStorageException reportProviderFailure(
            String operation,
            String translationOperation,
            @Nullable String objectKey,
            boolean configurationOperation,
            Exception exception
    ) {
        ImageStorageException translated = exceptionTranslator.translate(
                translationOperation,
                objectKey,
                exception
        );
        S3ProviderErrorMetadata metadata = S3ProviderErrorMetadata.from(
                exception,
                configurationOperation
        );
        ExternalApiFailure failure = new ExternalApiFailure(
                PROVIDER,
                operation,
                metadata.failureType(),
                metadata.status(),
                metadata.code(),
                metadata.requestId()
        );
        if (metadata.requiresImmediateAction()) {
            externalApiLogger.error(failure, exception);
        } else {
            externalApiLogger.warn(failure, exception);
        }
        return translated;
    }

    ImageStorageException reportInternalFailure(
            String operation,
            String translationOperation,
            @Nullable String objectKey,
            String failureType,
            Exception exception
    ) {
        ImageStorageException translated = exceptionTranslator.translate(
                translationOperation,
                objectKey,
                exception
        );
        externalApiLogger.error(
                new ExternalApiFailure(PROVIDER, operation, resolveFailureType(exception, failureType), null, null, null),
                exception
        );
        return translated;
    }

    private static String resolveFailureType(Exception exception, String defaultFailureType) {
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (cause instanceof CwebpConversionException conversionException) {
                return conversionException.failureType();
            }
            cause = cause.getCause();
        }
        return defaultFailureType;
    }

    ImageStorageException reportValidationFailure(
            String operation,
            String translationOperation,
            @Nullable String objectKey,
            IllegalArgumentException exception
    ) {
        ImageStorageException translated = exceptionTranslator.translate(
                translationOperation,
                objectKey,
                exception
        );
        externalApiLogger.warn(
                new ExternalApiFailure(
                        PROVIDER,
                        operation,
                        REQUEST_VALIDATION_ERROR,
                        null,
                        null,
                        null
                ),
                exception
        );
        return translated;
    }

    ImageStorageException reportCompensationFailure(
            String operation,
            String translationOperation,
            @Nullable String objectKey,
            Exception exception
    ) {
        ImageStorageException translated = exceptionTranslator.translate(
                translationOperation,
                objectKey,
                exception
        );
        S3ProviderErrorMetadata metadata = S3ProviderErrorMetadata.from(exception, false);
        externalApiLogger.warnCompensation(
                new ExternalApiFailure(
                        PROVIDER,
                        operation,
                        metadata.failureType(),
                        metadata.status(),
                        metadata.code(),
                        metadata.requestId()
                ),
                exception
        );
        return translated;
    }
}
