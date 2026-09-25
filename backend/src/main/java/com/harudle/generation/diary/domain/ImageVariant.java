package com.harudle.generation.diary.domain;

public enum ImageVariant {

    // DETAIL 파일명을 바꿀 때는 기존 image-960.webp 키의 썸네일 조회와 삭제도 동작하도록 ImageVariantKeys를 수정한다.
    DETAIL(960, "image-960.webp"),
    THUMBNAIL(240, "image-240.webp");

    private final int width;
    private final String filename;

    ImageVariant(int width, String filename) {
        this.width = width;
        this.filename = filename;
    }

    public int width() {
        return width;
    }

    public String filename() {
        return filename;
    }
}
