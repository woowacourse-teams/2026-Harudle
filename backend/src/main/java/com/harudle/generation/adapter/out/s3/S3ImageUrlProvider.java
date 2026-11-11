package com.harudle.generation.adapter.out.s3;

import com.harudle.generation.config.S3StorageProperties;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import com.harudle.generation.diary.service.port.ImageUrlProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

public final class S3ImageUrlProvider implements ImageUrlProvider {

    private static final String OPERATION = "presign_get_object";
    private static final String TRANSLATION_OPERATION = "접근 URL 발급";
    private static final String REQUEST_PREPARATION_ERROR = "REQUEST_PREPARATION_ERROR";
    private static final String RESPONSE_PROCESSING_ERROR = "RESPONSE_PROCESSING_ERROR";

    private final S3Presigner s3Presigner;
    private final String bucket;
    private final Duration accessUrlTtl;
    private final S3FailureReporter failureReporter;
    private final S3ImageAccessPolicy accessPolicy;
    private final AwsCredentialsProvider credentialsProvider;

    public S3ImageUrlProvider(
            S3Presigner s3Presigner,
            S3StorageProperties properties,
            S3FailureReporter failureReporter,
            AwsCredentialsProvider credentialsProvider
    ) {
        this.s3Presigner = Objects.requireNonNull(s3Presigner, "S3 Presigner가 필요합니다.");
        Objects.requireNonNull(properties, "S3 저장소 설정이 필요합니다.");
        this.bucket = properties.bucket();
        this.accessPolicy = new S3ImageAccessPolicy(properties);
        this.accessUrlTtl = properties.accessUrlTtl();
        this.failureReporter = Objects.requireNonNull(failureReporter, "S3 실패 리포터가 필요합니다.");
        this.credentialsProvider = Objects.requireNonNull(credentialsProvider, "S3 서명 자격 제공자가 필요합니다.");
    }

    @Override
    public ImageAccessUrl createAccessUrl(String imageObjectKey) {
        validateObjectKey(imageObjectKey);
        return issue(imageObjectKey, Optional.empty());
    }

    SignedS3ImageUrl sign(String imageObjectKey) {
        validateObjectKey(imageObjectKey);
        AwsCredentials credentials = resolveCredentials(imageObjectKey);
        ImageAccessUrl accessUrl = issue(imageObjectKey, Optional.of(credentials));
        return new SignedS3ImageUrl(accessUrl, credentials.expirationTime().isPresent());
    }

    private ImageAccessUrl issue(String imageObjectKey, Optional<AwsCredentials> credentials) {
        GetObjectPresignRequest request = preparePresignRequest(imageObjectKey, credentials);
        PresignedGetObjectRequest response = presign(request, imageObjectKey);
        return toImageAccessUrl(response, imageObjectKey, credentials.flatMap(AwsCredentials::expirationTime));
    }

    private AwsCredentials resolveCredentials(String imageObjectKey) {
        try {
            return credentialsProvider.resolveCredentials();
        } catch (Exception exception) {
            throw failureReporter.reportProviderFailure(
                    OPERATION, TRANSLATION_OPERATION, imageObjectKey, true, exception
            );
        }
    }

    void validateObjectKey(String imageObjectKey) {
        try {
            accessPolicy.requireGenerated(imageObjectKey);
        } catch (IllegalArgumentException exception) {
            throw failureReporter.reportValidationFailure(
                    OPERATION,
                    TRANSLATION_OPERATION,
                    imageObjectKey,
                    exception
            );
        }
    }

    private GetObjectPresignRequest preparePresignRequest(
            String imageObjectKey,
            Optional<AwsCredentials> credentials
    ) {
        try {
            GetObjectRequest.Builder getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(imageObjectKey);
            credentials.ifPresent(snapshot -> getObjectRequest.overrideConfiguration(
                    builder -> builder.credentialsProvider(() -> snapshot)));
            return GetObjectPresignRequest.builder()
                    .signatureDuration(accessUrlTtl)
                    .getObjectRequest(getObjectRequest.build())
                    .build();
        } catch (Exception exception) {
            throw failureReporter.reportInternalFailure(
                    OPERATION,
                    TRANSLATION_OPERATION,
                    imageObjectKey,
                    REQUEST_PREPARATION_ERROR,
                    exception
            );
        }
    }

    private PresignedGetObjectRequest presign(GetObjectPresignRequest request, String imageObjectKey) {
        try {
            return s3Presigner.presignGetObject(request);
        } catch (Exception exception) {
            throw failureReporter.reportProviderFailure(
                    OPERATION,
                    TRANSLATION_OPERATION,
                    imageObjectKey,
                    true,
                    exception
            );
        }
    }

    private ImageAccessUrl toImageAccessUrl(
            PresignedGetObjectRequest response,
            String imageObjectKey,
            Optional<Instant> credentialExpiration
    ) {
        try {
            Instant signatureExpiration = response.expiration();
            Instant expiresAt = credentialExpiration.filter(expiration -> expiration.isBefore(signatureExpiration))
                    .orElse(signatureExpiration);
            return new ImageAccessUrl(
                    response.url().toURI(),
                    expiresAt
            );
        } catch (Exception exception) {
            throw failureReporter.reportInternalFailure(
                    OPERATION,
                    TRANSLATION_OPERATION,
                    imageObjectKey,
                    RESPONSE_PROCESSING_ERROR,
                    exception
            );
        }
    }
}
