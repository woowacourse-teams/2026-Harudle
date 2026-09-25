package com.harudle.generation.adapter.out.s3;

import com.harudle.generation.config.S3StorageProperties;
import com.harudle.generation.diary.domain.ImageVariantKeys;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
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
    private final ImageUploadPreparer uploadPreparer;
    private final S3FailureReporter failureReporter;

    public S3ImageStorage(
            S3Client s3Client,
            S3StorageProperties properties,
            ImageUploadPreparer uploadPreparer,
            S3FailureReporter failureReporter
    ) {
        this.s3Client = Objects.requireNonNull(s3Client, "S3Client가 필요합니다.");
        Objects.requireNonNull(properties, "S3 저장소 설정이 필요합니다.");
        this.bucket = properties.bucket();
        this.maxObjectSizeBytes = resolveMaxObjectSizeBytes(properties);
        this.uploadPreparer = Objects.requireNonNull(uploadPreparer, "이미지 업로드 준비기가 필요합니다.");
        this.failureReporter = Objects.requireNonNull(failureReporter, "S3 실패 리포터가 필요합니다.");
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
        requireGeneratedImage(generatedImage);
        PreparedStores prepared = prepareStores(generationId, generatedImage);
        putAll(prepared.uploads());
        return prepared.primaryKey();
    }

    private void requireGeneratedImage(GeneratedImage generatedImage) {
        if (generatedImage == null) {
            throw failureReporter.reportValidationFailure(
                    PUT_OBJECT,
                    STORE_TRANSLATION_OPERATION,
                    null,
                    new IllegalArgumentException("저장할 생성 이미지가 필요합니다.")
            );
        }
    }

    private PreparedStores prepareStores(UUID generationId, GeneratedImage generatedImage) {
        try {
            return prepareStores(uploadPreparer.prepare(generationId, generatedImage));
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

    private PreparedStores prepareStores(ImageUploadPreparer.UploadPlan plan) throws IOException {
        List<PreparedStore> uploads = new ArrayList<>();
        for (ImageUploadPreparer.Upload upload : plan.uploads()) {
            uploads.add(prepareStore(upload.objectKey(), upload.image()));
        }
        return new PreparedStores(plan.primaryKey(), List.copyOf(uploads));
    }

    private void putAll(List<PreparedStore> uploads) {
        List<String> storedKeys = new ArrayList<>();
        try {
            for (PreparedStore upload : uploads) {
                putPrepared(upload);
                storedKeys.add(upload.objectKey());
            }
        } catch (ImageStorageException exception) {
            deleteStoredImages(storedKeys, exception);
            throw exception;
        }
    }

    private void deleteStoredImages(List<String> storedKeys, ImageStorageException storeException) {
        // 성공이 확인된 객체만 정리하며, 삭제 실패가 원래 저장 오류를 가리지 않도록 한다.
        for (String storedKey : storedKeys) {
            try {
                deleteObject(prepareDeleteRequest(storedKey));
            } catch (ImageStorageException cleanupException) {
                storeException.addSuppressed(cleanupException);
            }
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
    public boolean restoreOptimizedIfMissing(String detailKey, GeneratedImage generatedImage) {
        requireGeneratedImage(generatedImage);
        PreparedStores prepared = prepareOptimizedStores(detailKey, generatedImage);
        // 기존 썸네일이 다른 그림일 수 있으므로, 변환과 크기 검증이 끝난 뒤 제거한다.
        deleteKeys(ImageVariantKeys.companionKeys(detailKey));
        if (!restorePreparedIfMissing(prepared.uploads().getLast())) {
            return restoreThumbnailFromDetail(detailKey);
        }
        for (PreparedStore upload : prepared.uploads()) {
            if (!upload.objectKey().equals(detailKey)) {
                restorePreparedIfMissing(upload);
            }
        }
        return true;
    }

    private PreparedStores prepareOptimizedStores(String detailKey, GeneratedImage image) {
        try {
            return prepareStores(uploadPreparer.prepareOptimized(detailKey, image));
        } catch (IllegalArgumentException exception) {
            throw failureReporter.reportValidationFailure(
                    PUT_OBJECT, STORE_TRANSLATION_OPERATION, detailKey, exception
            );
        } catch (Exception exception) {
            throw failureReporter.reportInternalFailure(
                    PUT_OBJECT, STORE_TRANSLATION_OPERATION, detailKey, REQUEST_PREPARATION_ERROR, exception
            );
        }
    }

    @Override
    public boolean restoreThumbnailFromDetail(String detailKey) {
        String thumbnailKey = ImageVariantKeys.toThumbnailKeyIfOptimizedDetail(detailKey);
        if (thumbnailKey.equals(detailKey)) {
            throw failureReporter.reportValidationFailure(
                    PUT_OBJECT, STORE_TRANSLATION_OPERATION, detailKey,
                    new IllegalArgumentException("최적화된 상세 이미지 키가 필요합니다.")
            );
        }
        if (exists(thumbnailKey)) {
            return false;
        }
        ReferenceImage detail = load(detailKey);
        PreparedStore thumbnail = prepareThumbnailStore(detailKey, detail);
        return restorePreparedIfMissing(thumbnail);
    }

    private PreparedStore prepareThumbnailStore(String detailKey, ReferenceImage detail) {
        try {
            GeneratedImage image = new GeneratedImage(detail.resource(), detail.mediaType());
            ImageUploadPreparer.Upload thumbnail = uploadPreparer.prepareThumbnailFromDetail(detailKey, image);
            return prepareStore(thumbnail.objectKey(), thumbnail.image());
        } catch (IllegalArgumentException exception) {
            throw failureReporter.reportValidationFailure(
                    PUT_OBJECT, STORE_TRANSLATION_OPERATION, detailKey, exception
            );
        } catch (Exception exception) {
            throw failureReporter.reportInternalFailure(
                    PUT_OBJECT, STORE_TRANSLATION_OPERATION, detailKey, REQUEST_PREPARATION_ERROR, exception
            );
        }
    }

    private boolean restorePreparedIfMissing(PreparedStore prepared) {
        return restoreIfMissing(prepared.objectKey(), new GeneratedImage(prepared.resource(),
                MediaType.parseMediaType(prepared.request().contentType())));
    }

    @Override
    public void delete(String imageObjectKey) {
        List<String> keys = new ArrayList<>();
        keys.add(imageObjectKey);
        keys.addAll(ImageVariantKeys.companionKeys(imageObjectKey));
        deleteKeys(keys);
    }

    private void deleteKeys(List<String> keys) {
        List<DeleteObjectRequest> requests = keys.stream()
                .map(this::prepareDeleteRequest)
                .toList();

        ImageStorageException firstFailure = null;
        for (DeleteObjectRequest request : requests) {
            try {
                deleteObject(request);
            } catch (ImageStorageException exception) {
                if (firstFailure == null) {
                    firstFailure = exception;
                } else {
                    firstFailure.addSuppressed(exception);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
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

    private record PreparedStores(String primaryKey, List<PreparedStore> uploads) {
    }
}
