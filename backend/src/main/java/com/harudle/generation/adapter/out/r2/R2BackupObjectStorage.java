package com.harudle.generation.adapter.out.r2;

import com.harudle.common.logging.ExternalApiFailure;
import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.config.R2StorageProperties;
import com.harudle.generation.diary.service.port.BackupObjectStorage;
import com.harudle.generation.diary.service.port.BackupStorageException;
import com.harudle.generation.diary.service.port.BackupStorageException.FailureType;
import com.harudle.generation.diary.service.port.dto.BackupObjectMetadata;
import com.harudle.generation.diary.service.port.dto.BackupUploadResult;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

public final class R2BackupObjectStorage implements BackupObjectStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger(R2BackupObjectStorage.class);
    private static final Set<String> AUTHENTICATION_CODES = Set.of(
            "InvalidAccessKeyId", "SignatureDoesNotMatch", "ExpiredToken", "InvalidToken"
    );
    private static final Set<String> CONFIGURATION_CODES = Set.of(
            "NoSuchBucket", "InvalidBucketName", "AuthorizationHeaderMalformed", "PermanentRedirect"
    );

    private final S3Client client;
    private final S3Presigner presigner;
    private final R2StorageProperties properties;
    private final R2ObjectKeyPolicy keyPolicy;
    private final int maxObjectSizeBytes;
    private final ExternalApiLogger externalApiLogger;

    public R2BackupObjectStorage(
            S3Client client,
            S3Presigner presigner,
            R2StorageProperties properties,
            ExternalApiLogger externalApiLogger
    ) {
        this.client = Objects.requireNonNull(client);
        this.presigner = Objects.requireNonNull(presigner);
        this.properties = Objects.requireNonNull(properties);
        this.externalApiLogger = Objects.requireNonNull(externalApiLogger);
        this.keyPolicy = new R2ObjectKeyPolicy(properties.environment());
        if (!properties.isMaxObjectSizeValid()) {
            throw new IllegalArgumentException("유효한 R2 객체 최대 크기가 필요합니다.");
        }
        this.maxObjectSizeBytes = Math.toIntExact(properties.maxObjectSize().toBytes());
    }

    @Override
    public Optional<BackupObjectMetadata> findMetadata(String objectKey) {
        requireKey("head_object", objectKey);
        HeadObjectResponse response;
        try {
            response = client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket()).key(objectKey).build());
        } catch (S3Exception exception) {
            if (isMissingObject("head_object", objectKey, exception)) {
                return Optional.empty();
            }
            throw providerFailure("head_object", objectKey, exception);
        } catch (Exception exception) {
            throw providerFailure("head_object", objectKey, exception);
        }
        try {
            validateSize(response.contentLength());
            MediaType mediaType = parseMediaType(objectKey, response.contentType());
            BackupObjectMetadata metadata = new BackupObjectMetadata(
                    objectKey, mediaType, response.contentLength(), response.eTag()
            );
            logResult("head_object", objectKey, "FOUND", null, metadata.size());
            return Optional.of(metadata);
        } catch (Exception exception) {
            throw failure("head_object", objectKey, FailureType.RESPONSE_PROCESSING_ERROR, exception);
        }
    }

    @Override
    public Optional<ReferenceImage> download(String objectKey) {
        requireKey("get_object", objectKey);
        ResponseInputStream<GetObjectResponse> response;
        try {
            response = client.getObject(GetObjectRequest.builder()
                    .bucket(properties.bucket()).key(objectKey).build());
        } catch (S3Exception exception) {
            if (isMissingObject("get_object", objectKey, exception)) {
                return Optional.empty();
            }
            throw providerFailure("get_object", objectKey, exception);
        } catch (Exception exception) {
            throw providerFailure("get_object", objectKey, exception);
        }
        try (response) {
            try {
                validateSize(response.response().contentLength());
                MediaType mediaType = parseMediaType(objectKey, response.response().contentType());
                byte[] bytes = readBytes(response);
                if (bytes.length != response.response().contentLength()) {
                    throw new IllegalArgumentException("백업 응답의 크기와 실제 원본 크기가 일치하지 않습니다.");
                }
                ReferenceImage original = new ReferenceImage(new ByteArrayResource(bytes), mediaType);
                logResult("get_object", objectKey, "DOWNLOADED", null, bytes.length);
                return Optional.of(original);
            } catch (IOException | RuntimeException exception) {
                // 크기가 큰 응답이나 잘못된 응답을 close 과정에서 끝까지 읽지 않는다.
                response.abort();
                throw exception;
            }
        } catch (IOException | SdkClientException exception) {
            throw providerFailure("get_object", objectKey, exception);
        } catch (Exception exception) {
            throw failure("get_object", objectKey, FailureType.RESPONSE_PROCESSING_ERROR, exception);
        }
    }

    @Override
    public BackupUploadResult uploadIfAbsent(String objectKey, GeneratedImage original) {
        requireKey("put_object", objectKey);
        byte[] bytes;
        try {
            Objects.requireNonNull(original, "백업할 이미지 원본이 필요합니다.");
            keyPolicy.requireMatchingMediaType(objectKey, original.mediaType());
            try (InputStream stream = original.resource().getInputStream()) {
                bytes = readBytes(stream);
            }
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw failure("put_object", objectKey, FailureType.REQUEST_VALIDATION_ERROR, exception);
        } catch (Exception exception) {
            throw failure("put_object", objectKey, FailureType.REQUEST_PREPARATION_ERROR, exception);
        }
        try {
            client.putObject(PutObjectRequest.builder()
                    .bucket(properties.bucket()).key(objectKey)
                    .contentType(original.mediaType().toString()).contentLength((long) bytes.length)
                    .ifNoneMatch("*").build(), RequestBody.fromBytes(bytes));
            logResult("put_object", objectKey, "UPLOADED", null, bytes.length);
            return BackupUploadResult.UPLOADED;
        } catch (S3Exception exception) {
            if (exception.statusCode() == 412) {
                // SDK 재시도 전의 PUT이 성공했을 수도 있다. 내용 검증은 백업 실행기가 담당한다.
                logResult("put_object", objectKey, "ALREADY_EXISTS", null, null);
                return BackupUploadResult.ALREADY_EXISTS;
            }
            throw providerFailure("put_object", objectKey, exception);
        } catch (Exception exception) {
            // 응답 유실을 포함한 실패에서도 원본이나 기존 백업을 삭제하지 않는다.
            throw providerFailure("put_object", objectKey, exception);
        }
    }

    @Override
    public ImageAccessUrl createAccessUrl(String objectKey) {
        requireKey("presign_get_object", objectKey);
        try {
            PresignedGetObjectRequest signed = presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(properties.accessUrlTtl())
                    .getObjectRequest(request -> request.bucket(properties.bucket()).key(objectKey))
                    .build());
            ImageAccessUrl result = new ImageAccessUrl(signed.url().toURI(), signed.expiration());
            logResult("presign_get_object", objectKey, "URL_CREATED", null, null);
            return result;
        } catch (Exception exception) {
            throw providerFailure("presign_get_object", objectKey, exception);
        }
    }

    private boolean isMissingObject(String operation, String objectKey, S3Exception exception) {
        if (exception.statusCode() != 404) {
            return false;
        }
        String code = errorCode(exception);
        if ("NoSuchKey".equals(code)) {
            logResult(operation, objectKey, "NOT_FOUND", null, null);
            return true;
        }
        if (code != null && !code.isBlank() && !"NotFound".equals(code) && !"404".equals(code)) {
            return false;
        }
        // HEAD에는 오류 본문이 없다. 버킷 404를 객체 없음으로 오인하지 않도록 한 번 확인한다.
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(properties.bucket()).build());
        } catch (S3Exception bucketException) {
            FailureType type = bucketException.statusCode() == 404
                    ? FailureType.CONFIGURATION_ERROR : failureType(bucketException);
            throw failure(operation, objectKey, type, bucketException);
        } catch (Exception bucketException) {
            throw providerFailure(operation, objectKey, bucketException);
        }
        logResult(operation, objectKey, "NOT_FOUND", null, null);
        return true;
    }

    private void requireKey(String operation, String objectKey) {
        try {
            keyPolicy.requireOriginal(objectKey);
        } catch (IllegalArgumentException exception) {
            throw failure(operation, "invalid", FailureType.REQUEST_VALIDATION_ERROR, exception);
        }
    }

    private byte[] readBytes(InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(maxObjectSizeBytes + 1);
        validateSize((long) bytes.length);
        return bytes;
    }

    private void validateSize(@Nullable Long size) {
        if (size == null || size <= 0 || size > maxObjectSizeBytes) {
            throw new IllegalArgumentException("백업 원본 크기는 0바이트 초과 최대 허용 크기 이하여야 합니다.");
        }
    }

    private MediaType parseMediaType(String objectKey, @Nullable String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("백업 원본 MIME이 필요합니다.");
        }
        MediaType mediaType = MediaType.parseMediaType(contentType);
        keyPolicy.requireMatchingMediaType(objectKey, mediaType);
        return mediaType;
    }

    private BackupStorageException providerFailure(String operation, String key, Exception exception) {
        FailureType type = exception instanceof S3Exception s3Exception
                ? failureType(s3Exception) : FailureType.CLIENT_ERROR;
        return failure(operation, key, type, exception);
    }

    private FailureType failureType(S3Exception exception) {
        String code = errorCode(exception);
        if (exception.statusCode() == 401 || (code != null && AUTHENTICATION_CODES.contains(code))) {
            return FailureType.AUTHENTICATION_ERROR;
        }
        if (code != null && CONFIGURATION_CODES.contains(code)) {
            return FailureType.CONFIGURATION_ERROR;
        }
        if (exception.statusCode() == 403) {
            return FailureType.AUTHORIZATION_ERROR;
        }
        return FailureType.PROVIDER_ERROR;
    }

    private BackupStorageException failure(String operation, String key, FailureType type, Exception exception) {
        logResult(operation, key, "FAILED", type, null);
        S3Exception serviceException = exception instanceof S3Exception value ? value : null;
        ExternalApiFailure externalFailure = new ExternalApiFailure(
                "r2", operation, type.name(),
                serviceException == null ? null : Integer.toString(serviceException.statusCode()),
                serviceException == null ? null : errorCode(serviceException),
                serviceException == null ? null : serviceException.requestId()
        );
        if (type == FailureType.AUTHENTICATION_ERROR || type == FailureType.AUTHORIZATION_ERROR
                || type == FailureType.CONFIGURATION_ERROR) {
            externalApiLogger.error(externalFailure, exception);
        } else {
            externalApiLogger.warn(externalFailure, exception);
        }
        return new BackupStorageException(type, exception);
    }

    private @Nullable String errorCode(S3Exception exception) {
        return exception.awsErrorDetails() == null ? null : exception.awsErrorDetails().errorCode();
    }

    private void logResult(
            String operation, String key, String result, @Nullable FailureType type, @Nullable Number size
    ) {
        LOGGER.atInfo().addKeyValue("event", "r2_object_operation")
                .addKeyValue("provider", "r2").addKeyValue("operation", operation)
                .addKeyValue("bucket", properties.bucket()).addKeyValue("objectKey", key)
                .addKeyValue("result", result).addKeyValue("size", size == null ? "none" : size)
                .addKeyValue("failureType", type == null ? "none" : type.name())
                .log("event=r2_object_operation operation={} bucket={} objectKey={} result={} size={} failureType={}",
                        operation, properties.bucket(), key, result, size == null ? "none" : size,
                        type == null ? "none" : type.name());
    }
}
