package com.harudle.generation.diary.domain;

public final class ImageVariantKeys {

    public static final String DETAIL_FILENAME = "image-960.webp";
    public static final String THUMBNAIL_FILENAME = "image-240.webp";

    private ImageVariantKeys() {
    }

    public static String toThumbnailKeyIfOptimizedDetail(String imageObjectKey) {
        if (isOptimizedDetailKey(imageObjectKey)) {
            return imageObjectKey.substring(0, imageObjectKey.length() - DETAIL_FILENAME.length())
                    + THUMBNAIL_FILENAME;
        }
        return imageObjectKey;
    }

    public static boolean isOptimizedDetailKey(String imageObjectKey) {
        return imageObjectKey != null && imageObjectKey.endsWith("/" + DETAIL_FILENAME);
    }
}
