package com.harudle.generation.adapter.out.s3;

import com.harudle.generation.configuration.S3StorageProperties;
import com.harudle.generation.service.port.ImageAccessUrl;
import com.harudle.generation.service.port.ImageUrlProvider;
import java.time.Duration;
import java.util.Objects;
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

    public S3ImageUrlProvider(
            S3Presigner s3Presigner,
            S3StorageProperties properties,
            S3FailureReporter failureReporter
    ) {
        this.s3Presigner = Objects.requireNonNull(s3Presigner, "S3 Presigner가 필요합니다.");
        Objects.requireNonNull(properties, "S3 저장소 설정이 필요합니다.");
        this.bucket = properties.bucket();
        this.accessUrlTtl = properties.accessUrlTtl();
        this.failureReporter = Objects.requireNonNull(failureReporter, "S3 실패 리포터가 필요합니다.");
    }

    @Override
    public ImageAccessUrl createAccessUrl(String imageObjectKey) {
        try {
            S3ObjectKeyValidator.validate(imageObjectKey);
        } catch (IllegalArgumentException exception) {
            throw failureReporter.reportValidationFailure(
                    OPERATION,
                    TRANSLATION_OPERATION,
                    imageObjectKey,
                    exception
            );
        }

        GetObjectPresignRequest presignRequest;
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(imageObjectKey)
                    .build();
            presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(accessUrlTtl)
                    .getObjectRequest(getObjectRequest)
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

        PresignedGetObjectRequest presignedRequest;
        try {
            presignedRequest = s3Presigner.presignGetObject(presignRequest);
        } catch (Exception exception) {
            throw failureReporter.reportProviderFailure(
                    OPERATION,
                    TRANSLATION_OPERATION,
                    imageObjectKey,
                    true,
                    exception
            );
        }

        try {
            return new ImageAccessUrl(
                    presignedRequest.url().toURI(),
                    presignedRequest.expiration()
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
