package com.harudle.generation.adapter.out.s3;

import com.harudle.generation.diary.domain.ImageVariant;
import com.harudle.generation.diary.domain.ImageVariantKeys;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.MediaType;

public final class ImageUploadPreparer {

    private static final int MAX_SOURCE_IMAGE_SIZE_BYTES = 20 * 1024 * 1024;

    private final ImageObjectKeyFactory objectKeyFactory;
    private final ImageVariantEncoder variantEncoder;

    public ImageUploadPreparer(ImageObjectKeyFactory objectKeyFactory, ImageVariantEncoder variantEncoder) {
        this.objectKeyFactory = Objects.requireNonNull(objectKeyFactory, "Object Key 생성기가 필요합니다.");
        this.variantEncoder = Objects.requireNonNull(variantEncoder, "이미지 변환기가 필요합니다.");
    }

    public UploadPlan prepare(UUID generationId, GeneratedImage image) {
        validateSourceImageSize(image);
        if (!isConvertible(image.mediaType())) {
            String key = objectKeyFactory.create(generationId, image.mediaType());
            return new UploadPlan(key, List.of(new Upload(key, image)));
        }

        return prepareVariants(objectKeyFactory.createOptimized(generationId), image);
    }

    public UploadPlan prepareOptimized(String detailKey, GeneratedImage image) {
        requireOptimizedDetailKey(detailKey);
        validateSourceImageSize(image);
        return prepareVariants(detailKey, image);
    }

    public Upload prepareThumbnailFromDetail(String detailKey, GeneratedImage detailImage) {
        requireOptimizedDetailKey(detailKey);
        validateSourceImageSize(detailImage);
        GeneratedImage thumbnail = variantEncoder.encode(detailImage).get(ImageVariant.THUMBNAIL);
        return new Upload(ImageVariantKeys.forVariant(detailKey, ImageVariant.THUMBNAIL), thumbnail);
    }

    private UploadPlan prepareVariants(String primaryKey, GeneratedImage image) {
        Map<ImageVariant, GeneratedImage> images = variantEncoder.encode(image);
        List<Upload> uploads = new ArrayList<>();
        for (ImageVariant variant : ImageVariant.values()) {
            if (variant != ImageVariant.DETAIL) {
                uploads.add(new Upload(ImageVariantKeys.forVariant(primaryKey, variant), images.get(variant)));
            }
        }
        uploads.add(new Upload(primaryKey, images.get(ImageVariant.DETAIL)));
        return new UploadPlan(primaryKey, uploads);
    }

    private static void requireOptimizedDetailKey(String detailKey) {
        if (!ImageVariantKeys.isOptimizedDetailKey(detailKey)) {
            throw new IllegalArgumentException("최적화된 상세 이미지 키가 필요합니다.");
        }
    }

    private static void validateSourceImageSize(GeneratedImage image) {
        try {
            long size = image.resource().contentLength();
            if (size <= 0 || size > MAX_SOURCE_IMAGE_SIZE_BYTES) {
                throw new IllegalArgumentException("입력 이미지 크기가 허용 범위를 벗어났습니다.");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("입력 이미지 크기를 확인할 수 없습니다.", exception);
        }
    }

    private static boolean isConvertible(MediaType mediaType) {
        return MediaType.IMAGE_PNG.isCompatibleWith(mediaType)
                || MediaType.IMAGE_JPEG.isCompatibleWith(mediaType);
    }

    public record Upload(String objectKey, GeneratedImage image) {
        public Upload {
            Objects.requireNonNull(objectKey, "업로드할 객체 키가 필요합니다.");
            Objects.requireNonNull(image, "업로드할 이미지가 필요합니다.");
        }
    }

    public record UploadPlan(String primaryKey, List<Upload> uploads) {
        public UploadPlan {
            uploads = List.copyOf(uploads);
            if (uploads.isEmpty() || !uploads.getLast().objectKey().equals(primaryKey)) {
                throw new IllegalArgumentException("대표 이미지는 업로드 목록의 마지막에 있어야 합니다.");
            }
            if (uploads.stream().map(Upload::objectKey).distinct().count() != uploads.size()) {
                throw new IllegalArgumentException("업로드할 객체 키는 중복될 수 없습니다.");
            }
        }
    }
}
