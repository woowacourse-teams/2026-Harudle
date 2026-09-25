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

    public static List<String> companionKeys(String imageObjectKey) {
        if (!isOptimizedDetailKey(imageObjectKey)) {
            return List.of();
        }
        return Arrays.stream(ImageVariant.values())
                .filter(variant -> variant != ImageVariant.DETAIL)
                .map(variant -> forVariant(imageObjectKey, variant))
                .toList();
    }
}
