package com.harudle.generation.adapter.out.s3;

import com.harudle.generation.configuration.S3StorageProperties;
import com.harudle.generation.service.port.GeneratedImage;
import com.harudle.generation.service.port.ImageStorage;
import com.harudle.generation.service.port.ImageStorageException;
import com.harudle.generation.service.port.ReferenceImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public final class S3ImageStorage implements ImageStorage {

    private static final String GET_OBJECT = "get_object";
    private static final String PUT_OBJECT = "put_object";
    private static final String DELETE_OBJECT = "delete_object";
    private static final String LOAD_TRANSLATION_OPERATION = "조회";
    private static final String STORE_TRANSLATION_OPERATION = "저장";
    private static final String DELETE_TRANSLATION_OPERATION = "삭제";
    private static final String REQUEST_PREPARATION_ERROR = "REQUEST_PREPARATION_ERROR";
    private static final String RESPONSE_PROCESSING_ERROR = "RESPONSE_PROCESSING_ERROR";

    private final S3Client s3Client;
    private final String bucket;
    private final int maxObjectSizeBytes;
    private final ImageObjectKeyFactory objectKeyFactory;
    private final S3FailureReporter failureReporter;

    public S3ImageStorage(
            S3Client s3Client,
            S3StorageProperties properties,
            ImageObjectKeyFactory objectKeyFactory,
            S3FailureReporter failureReporter
    ) {
        this.s3Client = Objects.requireNonNull(s3Client, "S3Client가 필요합니다.");
        Objects.requireNonNull(properties, "S3 저장소 설정이 필요합니다.");
        this.bucket = properties.bucket();
        this.maxObjectSizeBytes = resolveMaxObjectSizeBytes(properties);
        this.objectKeyFactory = Objects.requireNonNull(objectKeyFactory, "Object Key 생성기가 필요합니다.");
        this.failureReporter = Objects.requireNonNull(failureReporter, "S3 실패 리포터가 필요합니다.");
    }

    @Override
    public ReferenceImage load(String imageObjectKey) {
        GetObjectRequest request;
        try {
            S3ObjectKeyValidator.validate(imageObjectKey);
            request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(imageObjectKey)
                    .build();
        } catch (Exception exception) {
            throw failureReporter.reportInternalFailure(
                    GET_OBJECT,
                    LOAD_TRANSLATION_OPERATION,
                    imageObjectKey,
                    REQUEST_PREPARATION_ERROR,
                    exception
            );
        }

        ResponseInputStream<GetObjectResponse> response;
        try {
            response = s3Client.getObject(request);
        } catch (Exception exception) {
            throw failureReporter.reportProviderFailure(
                    GET_OBJECT,
                    LOAD_TRANSLATION_OPERATION,
                    imageObjectKey,
                    false,
                    exception
            );
        }

        try (response) {
            validateObjectSize(response.response().contentLength());
            MediaType mediaType = parseImageMediaType(response.response().contentType());
            byte[] imageBytes = readImageBytes(response);
            return new ReferenceImage(new ByteArrayResource(imageBytes), mediaType);
        } catch (Exception exception) {
            throw failureReporter.reportInternalFailure(
                    GET_OBJECT,
                    LOAD_TRANSLATION_OPERATION,
                    imageObjectKey,
                    RESPONSE_PROCESSING_ERROR,
                    exception
            );
        }
    }

    @Override
    public String store(UUID generationId, GeneratedImage generatedImage) {
        PreparedStore preparedStore;
        try {
            preparedStore = prepareStore(generationId, generatedImage);
        } catch (Exception exception) {
            throw failureReporter.reportInternalFailure(
                    PUT_OBJECT,
                    STORE_TRANSLATION_OPERATION,
                    null,
                    REQUEST_PREPARATION_ERROR,
                    exception
            );
        }

        boolean putAttempted = false;
        try {
            try (InputStream inputStream = preparedStore.resource().getInputStream()) {
                RequestBody requestBody = RequestBody.fromInputStream(
                        inputStream,
                        preparedStore.contentLength()
                );
                putAttempted = true;
                s3Client.putObject(preparedStore.request(), requestBody);
            }
            return preparedStore.objectKey();
        } catch (Exception exception) {
            ImageStorageException storeException = putAttempted
                    ? failureReporter.reportProviderFailure(
                            PUT_OBJECT,
                            STORE_TRANSLATION_OPERATION,
                            preparedStore.objectKey(),
                            false,
                            exception
                    )
                    : failureReporter.reportInternalFailure(
                            PUT_OBJECT,
                            STORE_TRANSLATION_OPERATION,
                            preparedStore.objectKey(),
                            REQUEST_PREPARATION_ERROR,
                            exception
                    );
            compensateStoreFailure(preparedStore.objectKey(), putAttempted, storeException);
            throw storeException;
        }
    }

    @Override
    public void delete(String imageObjectKey) {
        DeleteObjectRequest request;
        try {
            S3ObjectKeyValidator.validate(imageObjectKey);
            request = DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(imageObjectKey)
                    .build();
        } catch (Exception exception) {
            throw failureReporter.reportInternalFailure(
                    DELETE_OBJECT,
                    DELETE_TRANSLATION_OPERATION,
                    imageObjectKey,
                    REQUEST_PREPARATION_ERROR,
                    exception
            );
        }

        try {
            s3Client.deleteObject(request);
        } catch (Exception exception) {
            throw failureReporter.reportProviderFailure(
                    DELETE_OBJECT,
                    DELETE_TRANSLATION_OPERATION,
                    imageObjectKey,
                    false,
                    exception
            );
        }
    }

    private PreparedStore prepareStore(UUID generationId, GeneratedImage generatedImage) throws IOException {
        Objects.requireNonNull(generatedImage, "저장할 생성 이미지가 필요합니다.");
        Resource resource = generatedImage.resource();
        long contentLength = resource.contentLength();
        validateObjectSize(contentLength);
        String imageObjectKey = objectKeyFactory.create(generationId, generatedImage.mediaType());
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(imageObjectKey)
                .contentType(generatedImage.mediaType().toString())
                .contentLength(contentLength)
                .build();
        return new PreparedStore(imageObjectKey, resource, contentLength, request);
    }

    private byte[] readImageBytes(ResponseInputStream<GetObjectResponse> response) throws IOException {
        byte[] imageBytes = response.readNBytes(maxObjectSizeBytes + 1);
        validateObjectSize(imageBytes.length);
        return imageBytes;
    }

    private void compensateStoreFailure(
            String imageObjectKey,
            boolean putAttempted,
            ImageStorageException storeException
    ) {
        if (!putAttempted) {
            return;
        }

        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(imageObjectKey)
                    .build();
            s3Client.deleteObject(request);
        } catch (Exception exception) {
            ImageStorageException compensationException = failureReporter.reportCompensationFailure(
                    DELETE_OBJECT,
                    DELETE_TRANSLATION_OPERATION,
                    imageObjectKey,
                    exception
            );
            storeException.addSuppressed(compensationException);
        }
    }

    private void validateObjectSize(Long contentLength) {
        if (contentLength == null) {
            throw new IllegalArgumentException("S3 이미지 객체의 크기 정보가 필요합니다.");
        }
        validateObjectSize(contentLength.longValue());
    }

    private void validateObjectSize(long contentLength) {
        if (contentLength <= 0 || contentLength > maxObjectSizeBytes) {
            throw new IllegalArgumentException("S3 이미지 객체 크기가 허용 범위를 벗어났습니다.");
        }
    }

    private static MediaType parseImageMediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("S3 이미지 객체의 Content-Type이 필요합니다.");
        }

        MediaType mediaType = MediaType.parseMediaType(contentType);
        if (!mediaType.getType().equalsIgnoreCase("image") || mediaType.isWildcardSubtype()) {
            throw new IllegalArgumentException("S3 객체의 Content-Type은 구체적인 image/* 타입이어야 합니다.");
        }
        return mediaType;
    }

    private static int resolveMaxObjectSizeBytes(S3StorageProperties properties) {
        long maxObjectSizeBytes = properties.maxObjectSize().toBytes();
        if (maxObjectSizeBytes <= 0 || maxObjectSizeBytes >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("S3 객체 최대 크기는 2GiB 미만의 양수여야 합니다.");
        }
        return Math.toIntExact(maxObjectSizeBytes);
    }

    private record PreparedStore(
            String objectKey,
            Resource resource,
            long contentLength,
            PutObjectRequest request
    ) {
    }
}
