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

    private final S3Client s3Client;
    private final String bucket;
    private final int maxObjectSizeBytes;
    private final ImageObjectKeyFactory objectKeyFactory;
    private final S3ExceptionTranslator exceptionTranslator;

    public S3ImageStorage(
            S3Client s3Client,
            S3StorageProperties properties,
            ImageObjectKeyFactory objectKeyFactory,
            S3ExceptionTranslator exceptionTranslator
    ) {
        this.s3Client = Objects.requireNonNull(s3Client, "S3Client가 필요합니다.");
        Objects.requireNonNull(properties, "S3 저장소 설정이 필요합니다.");
        this.bucket = properties.bucket();
        this.maxObjectSizeBytes = resolveMaxObjectSizeBytes(properties);
        this.objectKeyFactory = Objects.requireNonNull(objectKeyFactory, "Object Key 생성기가 필요합니다.");
        this.exceptionTranslator = Objects.requireNonNull(exceptionTranslator, "S3 예외 변환기가 필요합니다.");
    }

    @Override
    public ReferenceImage load(String imageObjectKey) {
        try {
            S3ObjectKeyValidator.validate(imageObjectKey);
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(imageObjectKey)
                    .build();

            try (ResponseInputStream<GetObjectResponse> response = s3Client.getObject(request)) {
                validateObjectSize(response.response().contentLength());
                MediaType mediaType = parseImageMediaType(response.response().contentType());
                byte[] imageBytes = readImageBytes(response);
                return new ReferenceImage(new ByteArrayResource(imageBytes), mediaType);
            }
        } catch (Exception exception) {
            throw exceptionTranslator.translate("조회", imageObjectKey, exception);
        }
    }

    @Override
    public String store(UUID generationId, GeneratedImage generatedImage) {
        String imageObjectKey = null;
        boolean putAttempted = false;
        try {
            Objects.requireNonNull(generatedImage, "저장할 생성 이미지가 필요합니다.");
            Resource resource = generatedImage.resource();
            long contentLength = resource.contentLength();
            validateObjectSize(contentLength);
            imageObjectKey = objectKeyFactory.create(generationId, generatedImage.mediaType());

            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(imageObjectKey)
                    .contentType(generatedImage.mediaType().toString())
                    .contentLength(contentLength)
                    .build();

            try (InputStream inputStream = resource.getInputStream()) {
                RequestBody requestBody = RequestBody.fromInputStream(inputStream, contentLength);
                putAttempted = true;
                s3Client.putObject(request, requestBody);
            }
            return imageObjectKey;
        } catch (Exception exception) {
            ImageStorageException storeException = exceptionTranslator.translate("저장", imageObjectKey, exception);
            compensateStoreFailure(imageObjectKey, putAttempted, storeException);
            throw storeException;
        }
    }

    @Override
    public void delete(String imageObjectKey) {
        try {
            S3ObjectKeyValidator.validate(imageObjectKey);
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(imageObjectKey)
                    .build();

            s3Client.deleteObject(request);
        } catch (Exception exception) {
            throw exceptionTranslator.translate("삭제", imageObjectKey, exception);
        }
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
            delete(imageObjectKey);
        } catch (Exception deleteException) {
            storeException.addSuppressed(deleteException);
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
}
