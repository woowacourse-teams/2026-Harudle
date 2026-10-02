package com.harudle.generation.diary.domain;

import java.util.Arrays;
import java.util.List;

public final class ImageVariantKeys {

    private ImageVariantKeys() {
    }

    public static String toThumbnailKeyIfOptimizedDetail(String imageObjectKey) {
        if (isOptimizedDetailKey(imageObjectKey)) {
            return forVariant(imageObjectKey, ImageVariant.THUMBNAIL);
        }
        return imageObjectKey;
    }

    public static boolean isOptimizedDetailKey(String imageObjectKey) {
        return imageObjectKey != null && imageObjectKey.endsWith("/" + ImageVariant.DETAIL.filename());
    }

    public static String forVariant(String detailKey, ImageVariant variant) {
        if (!isOptimizedDetailKey(detailKey)) {
            throw new IllegalArgumentException("최적화된 상세 이미지 키가 필요합니다.");
        }
        return detailKey.substring(0, detailKey.lastIndexOf('/') + 1) + variant.filename();
    }

    public static List<String> derivedImageKeysExceptDetail(String imageObjectKey) {
        if (!isOptimizedDetailKey(imageObjectKey)) {
            return List.of();
        }
        return Arrays.stream(ImageVariant.values())
                .filter(variant -> variant != ImageVariant.DETAIL)
                .map(variant -> forVariant(imageObjectKey, variant))
                .toList();
    }

    public static String originalImageKey(String detailKey, String subtype) {
        if (!isOptimizedDetailKey(detailKey)) {
            throw new IllegalArgumentException("최적화된 상세 이미지 키가 필요합니다.");
        }
        String extension = switch (subtype) {
            case "png", "webp" -> subtype;
            case "jpeg" -> "jpg";
            default -> throw new IllegalArgumentException("지원하지 않는 원본 이미지 형식입니다: " + subtype);
        };
        return detailKey.substring(0, detailKey.lastIndexOf('/') + 1) + "image." + extension;
    }

    public static List<String> originalImageKeyCandidates(String detailKey) {
        if (!isOptimizedDetailKey(detailKey)) {
            return List.of();
        }
        return List.of(originalImageKey(detailKey, "png"), originalImageKey(detailKey, "jpeg"),
                originalImageKey(detailKey, "webp"));
    }

    /** 폴더 경로를 유지해 백업용 원본 키 후보를 만든다. 실제 존재 여부는 저장소 조회 시 확인한다. */
    public static List<String> originalImageKeyCandidatesForBackup(String imageObjectKey) {
        if (imageObjectKey == null || imageObjectKey.isBlank()) {
            throw new IllegalArgumentException("백업할 이미지 Object Key가 필요합니다.");
        }
        if (isOptimizedDetailKey(imageObjectKey)) {
            return originalImageKeyCandidates(imageObjectKey);
        }

        String filename = imageObjectKey.substring(imageObjectKey.lastIndexOf('/') + 1);
        return switch (filename) {
            case "image.png", "image.jpg", "image.webp" -> List.of(imageObjectKey);
            default -> throw new IllegalArgumentException("백업할 원본 또는 최적화된 상세 이미지 키가 필요합니다.");
        };
    }
}
