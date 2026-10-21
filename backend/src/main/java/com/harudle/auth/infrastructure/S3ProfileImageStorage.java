package com.harudle.auth.infrastructure;

import com.harudle.auth.application.ProfileImageStorageException;
import com.harudle.generation.config.S3StorageProperties;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

public final class S3ProfileImageStorage {

    private static final String WEBP_CONTENT_TYPE = "image/webp";
    private static final Duration DELETE_TIMEOUT = Duration.ofSeconds(2);
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final String bucket;
    private final String profilePrefix;
    private final Pattern profileKeyPattern;
    private final long maxObjectSizeBytes;
    private final Duration accessUrlTtl;

    public S3ProfileImageStorage(
            S3Client s3Client,
            S3Presigner s3Presigner,
            S3StorageProperties properties
    ) {
        this.s3Client = Objects.requireNonNull(s3Client, "S3Client가 필요합니다.");
        this.s3Presigner = Objects.requireNonNull(s3Presigner, "S3 Presigner가 필요합니다.");
        Objects.requireNonNull(properties, "S3 저장소 설정이 필요합니다.");

        if (!properties.isEnvironmentMatched()
                || !properties.isAccessUrlTtlValid()
                || !properties.isMaxObjectSizePositive()) {
            throw new IllegalArgumentException("프로필 이미지 저장소의 환경과 크기 및 URL 유효 시간 설정이 올바르지 않습니다.");
        }

        this.bucket = properties.bucket();
        this.profilePrefix = "harudle/profile-images/" + properties.environment() + "/";
        this.profileKeyPattern = Pattern.compile(
                Pattern.quote(profilePrefix) + UUID_PATTERN + "/" + UUID_PATTERN + "\\.webp"
        );
        this.maxObjectSizeBytes = properties.maxObjectSize().toBytes();
        this.accessUrlTtl = properties.accessUrlTtl();
    }

    public String store(UUID userId, byte[] webpBytes) {
        Objects.requireNonNull(userId, "userId는 필수입니다.");
        validateWebp(webpBytes);

        String objectKey = profilePrefix + userId + "/" + UUID.randomUUID() + ".webp";
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(WEBP_CONTENT_TYPE)
                .contentLength((long) webpBytes.length)
                .ifNoneMatch("*")
                .build();

        try {
            s3Client.putObject(request, RequestBody.fromBytes(webpBytes));
            return objectKey;
        } catch (SdkException exception) {
            // 업로드 결과가 불명확해도 정리할 대상을 확인할 수 있도록 경로를 보존한다.
            throw new ProfileImageStorageException("프로필 이미지 저장에 실패했습니다.", objectKey, exception);
        }
    }

    public void delete(String objectKey) {
        validateObjectKey(objectKey);
        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .overrideConfiguration(config -> config.apiCallTimeout(DELETE_TIMEOUT))
                .build();

        try {
            s3Client.deleteObject(request);
        } catch (SdkException exception) {
            throw new ProfileImageStorageException("프로필 이미지 삭제에 실패했습니다.", objectKey, exception);
        }
    }

    public ImageAccessUrl createAccessUrl(String objectKey) {
        validateObjectKey(objectKey);
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();
        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(accessUrlTtl)
                .getObjectRequest(getRequest)
                .build();

        try {
            PresignedGetObjectRequest response = s3Presigner.presignGetObject(request);
            return new ImageAccessUrl(response.url().toURI(), response.expiration());
        } catch (SdkException | URISyntaxException exception) {
            throw new ProfileImageStorageException("프로필 이미지 조회 주소 발급에 실패했습니다.", objectKey, exception);
        }
    }

    private void validateObjectKey(String objectKey) {
        if (objectKey == null || !profileKeyPattern.matcher(objectKey).matches()) {
            throw new IllegalArgumentException("현재 환경의 프로필 이미지 경로만 사용할 수 있습니다.");
        }
    }

    private void validateWebp(byte[] bytes) {
        if (bytes == null || bytes.length < 12 || bytes.length > maxObjectSizeBytes
                || bytes[0] != 'R' || bytes[1] != 'I' || bytes[2] != 'F' || bytes[3] != 'F'
                || bytes[8] != 'W' || bytes[9] != 'E' || bytes[10] != 'B' || bytes[11] != 'P') {
            throw new IllegalArgumentException("저장 가능한 크기의 변환된 WebP 파일이 필요합니다.");
        }
    }
}
