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
}
