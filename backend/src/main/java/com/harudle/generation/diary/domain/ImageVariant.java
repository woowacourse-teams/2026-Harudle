package com.harudle.generation.diary.domain;

public enum ImageVariant {

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

    // 파일명은 DB에 저장한 키의 일부이므로 기존 규격 변경 시 이전 키의 호환성도 고려한다.
    public String filename() {
        return filename;
    }
}
