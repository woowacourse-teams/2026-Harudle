package com.harudle.generation.adapter.out.s3;

import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;

@NullMarked
record S3ProviderErrorMetadata(
        String failureType,
        boolean requiresImmediateAction,
        @Nullable String status,
        @Nullable String code,
        @Nullable String requestId
) {

    private static final int MAX_CAUSE_DEPTH = 16;
    private static final Set<String> AUTHENTICATION_ERROR_CODES = Set.of(
            "ExpiredToken",
            "InvalidAccessKeyId",
            "InvalidToken",
            "SignatureDoesNotMatch",
            "TokenRefreshRequired",
            "UnrecognizedClientException"
    );
    private static final Set<String> AUTHORIZATION_ERROR_CODES = Set.of(
            "AccessDenied",
            "AllAccessDisabled",
            "AccountProblem"
    );
    private static final Set<String> CONFIGURATION_ERROR_CODES = Set.of(
            "AuthorizationHeaderMalformed",
            "IncorrectEndpoint",
            "InvalidBucketName",
            "NoSuchBucket",
            "PermanentRedirect"
    );

    static S3ProviderErrorMetadata from(Throwable exception, boolean configurationOperation) {
        AwsServiceException serviceException = findServiceException(exception);
        if (serviceException == null) {
            String failureType = configurationOperation ? "CONFIGURATION_ERROR" : "CLIENT_ERROR";
            return new S3ProviderErrorMetadata(
                    failureType,
                    configurationOperation,
                    null,
                    null,
                    null
            );
        }

        AwsErrorDetails errorDetails = serviceException.awsErrorDetails();
        String code = errorDetails == null ? null : errorDetails.errorCode();
        int statusCode = serviceException.statusCode();
        String failureType = resolveFailureType(statusCode, code);
        return new S3ProviderErrorMetadata(
                failureType,
                !failureType.equals("PROVIDER_ERROR"),
                Integer.toString(statusCode),
                code,
                serviceException.requestId()
        );
    }

    private static String resolveFailureType(int statusCode, @Nullable String code) {
        if (statusCode == 401 || contains(AUTHENTICATION_ERROR_CODES, code)) {
            return "AUTHENTICATION_ERROR";
        }
        if (statusCode == 403 || contains(AUTHORIZATION_ERROR_CODES, code)) {
            return "AUTHORIZATION_ERROR";
        }
        if (contains(CONFIGURATION_ERROR_CODES, code)) {
            return "CONFIGURATION_ERROR";
        }
        return "PROVIDER_ERROR";
    }

    private static boolean contains(Set<String> errorCodes, @Nullable String code) {
        return code != null && errorCodes.contains(code);
    }

    private static @Nullable AwsServiceException findServiceException(Throwable exception) {
        Throwable current = exception;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof AwsServiceException serviceException) {
                return serviceException;
            }
            current = current.getCause();
        }
        return null;
    }
}
