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

    private final S3Presigner s3Presigner;
    private final String bucket;
    private final Duration accessUrlTtl;
    private final S3ExceptionTranslator exceptionTranslator;

    public S3ImageUrlProvider(
            S3Presigner s3Presigner,
            S3StorageProperties properties,
            S3ExceptionTranslator exceptionTranslator
    ) {
        this.s3Presigner = Objects.requireNonNull(s3Presigner, "S3 Presigner가 필요합니다.");
        Objects.requireNonNull(properties, "S3 저장소 설정이 필요합니다.");
        this.bucket = properties.bucket();
        this.accessUrlTtl = properties.accessUrlTtl();
        this.exceptionTranslator = Objects.requireNonNull(exceptionTranslator, "S3 예외 변환기가 필요합니다.");
    }

    @Override
    public ImageAccessUrl createAccessUrl(String imageObjectKey) {
        try {
            S3ObjectKeyValidator.validate(imageObjectKey);
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(imageObjectKey)
                    .build();
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(accessUrlTtl)
                    .getObjectRequest(getObjectRequest)
                    .build();

            PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
            return new ImageAccessUrl(
                    presignedRequest.url().toURI(),
                    presignedRequest.expiration()
            );
        } catch (Exception exception) {
            throw exceptionTranslator.translate("접근 URL 발급", imageObjectKey, exception);
        }
    }
}
