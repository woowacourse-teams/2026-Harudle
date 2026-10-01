package com.harudle.generation.adapter.out.s3;

import com.harudle.generation.diary.service.port.ImageStorageException;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;

/** Bounded metric label; never contains an object key or provider-supplied error text. */
final class S3MetricFailureType {

    private static final int MAX_CAUSE_DEPTH = 16;

    private S3MetricFailureType() {
    }

    static String from(Throwable exception, boolean signingOperation) {
        if (exception instanceof ImageStorageException storageException
                && storageException.diagnosticType() != null) {
            return storageException.diagnosticType().name();
        }
        if (!containsSdkFailure(exception)) {
            return "OTHER";
        }
        return S3ProviderErrorMetadata.from(exception, signingOperation).failureType();
    }

    static ImageStorageException.DiagnosticType bounded(String failureType) {
        try {
            return ImageStorageException.DiagnosticType.valueOf(failureType);
        } catch (IllegalArgumentException exception) {
            return ImageStorageException.DiagnosticType.OTHER;
        }
    }

    private static boolean containsSdkFailure(Throwable exception) {
        Throwable current = exception;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof AwsServiceException || current instanceof SdkClientException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
