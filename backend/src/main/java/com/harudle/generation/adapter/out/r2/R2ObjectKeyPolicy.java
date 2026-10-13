package com.harudle.generation.adapter.out.r2;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.Arrays;
import java.util.Set;
import org.springframework.http.MediaType;

final class R2ObjectKeyPolicy {

    private static final Set<String> ORIGINAL_FILENAMES = Set.of("image.png", "image.jpg", "image.webp");
    private final String generatedRoot;

    R2ObjectKeyPolicy(String environment) {
        if (!"dev".equals(environment) && !"prod".equals(environment)) {
            throw new IllegalArgumentException("R2 실행 환경은 dev 또는 prod여야 합니다.");
        }
        this.generatedRoot = "harudle/generated/diary-images/" + environment + "/";
    }

    void requireOriginal(String objectKey) {
        if (objectKey == null || objectKey.isBlank() || objectKey.getBytes(UTF_8).length > 1024
                || objectKey.contains("\\") || objectKey.contains("%")
                || objectKey.chars().anyMatch(Character::isISOControl)
                || Arrays.stream(objectKey.split("/", -1)).anyMatch(this::invalidSegment)) {
            throw new IllegalArgumentException("유효한 백업 Object Key가 필요합니다.");
        }
        if (!objectKey.startsWith(generatedRoot)
                || !ORIGINAL_FILENAMES.contains(filename(objectKey))) {
            throw new IllegalArgumentException("현재 환경의 PNG, JPEG, WebP 원본만 접근할 수 있습니다.");
        }
    }

    void requireMatchingMediaType(String objectKey, MediaType mediaType) {
        MediaType expected = switch (filename(objectKey)) {
            case "image.png" -> MediaType.IMAGE_PNG;
            case "image.jpg" -> MediaType.IMAGE_JPEG;
            case "image.webp" -> MediaType.parseMediaType("image/webp");
            default -> throw new IllegalArgumentException("지원하지 않는 원본 파일명입니다.");
        };
        if (mediaType == null || mediaType.isWildcardType() || mediaType.isWildcardSubtype()
                || !expected.isCompatibleWith(mediaType)) {
            throw new IllegalArgumentException("원본 확장자와 MIME이 일치해야 합니다.");
        }
    }

    private String filename(String objectKey) {
        return objectKey.substring(objectKey.lastIndexOf('/') + 1);
    }

    private boolean invalidSegment(String segment) {
        return segment.isBlank() || segment.equals(".") || segment.equals("..");
    }
}
