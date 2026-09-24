package com.harudle.generation.adapter.out.s3;

import com.harudle.generation.config.S3StorageProperties;
import com.harudle.generation.diary.domain.ImageVariantKeys;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

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
    private final ImageVariantEncoder variantEncoder;

    public S3ImageStorage(
            S3Client s3Client,
            S3StorageProperties properties,
            ImageObjectKeyFactory objectKeyFactory,
            S3FailureReporter failureReporter,
            ImageVariantEncoder variantEncoder
    ) {
        this.s3Client = Objects.requireNonNull(s3Client, "S3Client가 필요합니다.");
        Objects.requireNonNull(properties, "S3 저장소 설정이 필요합니다.");
        this.bucket = properties.bucket();
        this.maxObjectSizeBytes = resolveMaxObjectSizeBytes(properties);
        this.objectKeyFactory = Objects.requireNonNull(objectKeyFactory, "Object Key 생성기가 필요합니다.");
        this.failureReporter = Objects.requireNonNull(failureReporter, "S3 실패 리포터가 필요합니다.");
        this.variantEncoder = Objects.requireNonNull(variantEncoder, "이미지 변환기가 필요합니다.");
    }

    @Override
    public ReferenceImage load(String imageObjectKey) {
        GetObjectRequest request = prepareLoadRequest(imageObjectKey);
        ResponseInputStream<GetObjectResponse> response = getObject(request);
        return readReferenceImage(response, imageObjectKey);
    }

    private GetObjectRequest prepareLoadRequest(String imageObjectKey) {
        try {
            S3ObjectKeyValidator.validate(imageObjectKey);
        } catch (IllegalArgumentException exception) {
            throw failureReporter.reportValidationFailure(
                    GET_OBJECT,
                    LOAD_TRANSLATION_OPERATION,
                    imageObjectKey,
                    exception
            );
        }

        try {
            return GetObjectRequest.builder()
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
    }

    private ResponseInputStream<GetObjectResponse> getObject(GetObjectRequest request) {
        try {
            return s3Client.getObject(request);
        } catch (Exception exception) {
            throw failureReporter.reportProviderFailure(
                    GET_OBJECT,
                    LOAD_TRANSLATION_OPERATION,
                    request.key(),
                    false,
                    exception
            );
        }
    }

    private ReferenceImage readReferenceImage(
            ResponseInputStream<GetObjectResponse> response,
            String imageObjectKey
    ) {
        try (response) {
            validateObjectSize(response.response().contentLength());
            MediaType mediaType = parseImageMediaType(response.response().contentType());
            byte[] imageBytes = readImageBytes(response);
            return new ReferenceImage(new ByteArrayResource(imageBytes), mediaType);
        } catch (IOException | SdkClientException exception) {
            throw failureReporter.reportProviderFailure(
                    GET_OBJECT,
                    LOAD_TRANSLATION_OPERATION,
                    imageObjectKey,
                    false,
                    exception
            );
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
        if (generatedImage == null) {
            throw failureReporter.reportValidationFailure(
                    PUT_OBJECT,
                    STORE_TRANSLATION_OPERATION,
                    null,
                    new IllegalArgumentException("저장할 생성 이미지가 필요합니다.")
            );
        }
        if (isConvertible(generatedImage.mediaType())) {
            return storeOptimized(generationId, generatedImage);
        }
        PreparedStore preparedStore;
        try {
            preparedStore = prepareStore(generationId, generatedImage);
        } catch (IllegalArgumentException exception) {
            throw failureReporter.reportValidationFailure(
                    PUT_OBJECT,
                    STORE_TRANSLATION_OPERATION,
                    null,
                    exception
            );
        } catch (Exception exception) {
            throw failureReporter.reportInternalFailure(
                    PUT_OBJECT,
                    STORE_TRANSLATION_OPERATION,
                    null,
                    REQUEST_PREPARATION_ERROR,
                    exception
            );
        }

        putPrepared(preparedStore);
        return preparedStore.objectKey();
    }

    private String storeOptimized(UUID generationId, GeneratedImage generatedImage) {
        PreparedImageVariants prepared = prepareOptimizedStores(generationId, generatedImage);

        // 상세 키를 DB에 기록하기 전에 두 객체를 모두 저장한다.
        putPrepared(prepared.thumbnail());
        try {
            putPrepared(prepared.detail());
        } catch (ImageStorageException exception) {
            deleteStoredThumbnail(prepared.thumbnail().objectKey(), exception);
            throw exception;
        }
        return prepared.detail().objectKey();
    }

    private PreparedImageVariants prepareOptimizedStores(UUID generationId, GeneratedImage generatedImage) {
        try {
            Objects.requireNonNull(generatedImage, "저장할 생성 이미지가 필요합니다.");
            validateObjectSize(generatedImage.resource().contentLength());
            ImageVariantEncoder.Variants variants = variantEncoder.encode(generatedImage);
            String detailKey = objectKeyFactory.createOptimized(generationId);
            PreparedStore detail = prepareStore(detailKey, variants.detail());
            PreparedStore thumbnail = prepareStore(
                    ImageVariantKeys.toThumbnailKeyIfOptimizedDetail(detailKey), variants.thumbnail()
            );
            return new PreparedImageVariants(thumbnail, detail);
        } catch (IllegalArgumentException exception) {
            throw failureReporter.reportValidationFailure(
                    PUT_OBJECT, STORE_TRANSLATION_OPERATION, null, exception
            );
        } catch (Exception exception) {
            throw failureReporter.reportInternalFailure(
                    PUT_OBJECT, STORE_TRANSLATION_OPERATION, null, REQUEST_PREPARATION_ERROR, exception
            );
        }
    }

    private void deleteStoredThumbnail(String thumbnailKey, ImageStorageException storeException) {
        // 첫 PUT은 성공이 확정됐으므로 상세 업로드가 실패하면 썸네일을 정리한다.
        try {
            deleteObject(prepareDeleteRequest(thumbnailKey));
        } catch (ImageStorageException cleanupException) {
            storeException.addSuppressed(cleanupException);
        }
    }

    private void putPrepared(PreparedStore preparedStore) {
        boolean putAttempted = false;
        try (InputStream inputStream = preparedStore.resource().getInputStream()) {
            RequestBody requestBody = RequestBody.fromInputStream(inputStream, preparedStore.contentLength());
            putAttempted = true;
            s3Client.putObject(preparedStore.request(), requestBody);
        } catch (Exception exception) {
            // PUT 결과가 불확실할 수 있으므로 이 객체는 여기서 삭제하지 않는다.
            throw translateStoreFailure(preparedStore.objectKey(), putAttempted, exception);
        }
    }

    private static boolean isConvertible(MediaType mediaType) {
        return MediaType.IMAGE_PNG.isCompatibleWith(mediaType)
                || MediaType.IMAGE_JPEG.isCompatibleWith(mediaType);
    }

    @Override
    public boolean exists(String imageObjectKey) {
        S3ObjectKeyValidator.validate(imageObjectKey);
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(imageObjectKey).build());
            return true;
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return false;
            }
            throw failureReporter.reportProviderFailure("head_object", LOAD_TRANSLATION_OPERATION,
                    imageObjectKey, false, exception);
        } catch (Exception exception) {
            throw failureReporter.reportProviderFailure("head_object", LOAD_TRANSLATION_OPERATION,
                    imageObjectKey, false, exception);
        }
    }

    @Override
    public boolean restoreIfMissing(String imageObjectKey, GeneratedImage generatedImage) {
        S3ObjectKeyValidator.validate(imageObjectKey);
        try {
            long length = generatedImage.resource().contentLength();
            validateObjectSize(length);
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket).key(imageObjectKey)
                    .contentType(generatedImage.mediaType().toString())
                    .contentLength(length).ifNoneMatch("*").build();
            try (InputStream stream = generatedImage.resource().getInputStream()) {
                s3Client.putObject(request, RequestBody.fromInputStream(stream, length));
            }
            return true;
        } catch (S3Exception exception) {
            if (exception.statusCode() == 412) {
                return false;
            }
            throw translateStoreFailure(imageObjectKey, true, exception);
        } catch (Exception exception) {
            // PUT 결과가 불확실해도 기존 키의 객체를 삭제하지 않는다.
            throw translateStoreFailure(imageObjectKey, true, exception);
        }
    }

    @Override
    public void delete(String imageObjectKey) {
        DeleteObjectRequest request = prepareDeleteRequest(imageObjectKey);
        deleteObject(request);
        if (ImageVariantKeys.isOptimizedDetailKey(imageObjectKey)) {
            deleteObject(prepareDeleteRequest(ImageVariantKeys.toThumbnailKeyIfOptimizedDetail(imageObjectKey)));
        }
    }

    private DeleteObjectRequest prepareDeleteRequest(String imageObjectKey) {
        try {
            S3ObjectKeyValidator.validate(imageObjectKey);
        } catch (IllegalArgumentException exception) {
            throw failureReporter.reportValidationFailure(
                    DELETE_OBJECT,
                    DELETE_TRANSLATION_OPERATION,
                    imageObjectKey,
                    exception
            );
        }

        try {
            return DeleteObjectRequest.builder()
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
    }

    private void deleteObject(DeleteObjectRequest request) {
        try {
            s3Client.deleteObject(request);
        } catch (Exception exception) {
            throw failureReporter.reportProviderFailure(
                    DELETE_OBJECT,
                    DELETE_TRANSLATION_OPERATION,
                    request.key(),
                    false,
                    exception
            );
        }
    }

    private ImageStorageException translateStoreFailure(
            String imageObjectKey,
            boolean putAttempted,
            Exception exception
    ) {
        if (putAttempted) {
            return failureReporter.reportProviderFailure(
                    PUT_OBJECT,
                    STORE_TRANSLATION_OPERATION,
                    imageObjectKey,
                    false,
                    exception
            );
        }
        return failureReporter.reportInternalFailure(
                PUT_OBJECT,
                STORE_TRANSLATION_OPERATION,
                imageObjectKey,
                REQUEST_PREPARATION_ERROR,
                exception
        );
    }

    private PreparedStore prepareStore(UUID generationId, GeneratedImage generatedImage) throws IOException {
        Objects.requireNonNull(generatedImage, "저장할 생성 이미지가 필요합니다.");
        return prepareStore(objectKeyFactory.create(generationId, generatedImage.mediaType()), generatedImage);
    }

    private PreparedStore prepareStore(String imageObjectKey, GeneratedImage generatedImage) throws IOException {
        Resource resource = generatedImage.resource();
        long contentLength = resource.contentLength();
        validateObjectSize(contentLength);
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

    private record PreparedImageVariants(PreparedStore thumbnail, PreparedStore detail) {
    }
}
