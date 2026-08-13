package com.harudle.generation.adapter.out.s3;

import com.harudle.generation.configuration.S3StorageProperties;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;

public final class ImageObjectKeyFactory {

    private final String generatedPrefix;

    public ImageObjectKeyFactory(S3StorageProperties properties) {
        Objects.requireNonNull(properties, "S3 저장소 설정이 필요합니다.");
        this.generatedPrefix = normalizePrefix(properties.generatedPrefix());
    }

    public String create(UUID generationId, MediaType mediaType) {
        Objects.requireNonNull(generationId, "생성 작업 ID가 필요합니다.");
        String extension = resolveExtension(mediaType);
        return "%s/%s/image.%s".formatted(generatedPrefix, generationId, extension);
    }

    private static String resolveExtension(MediaType mediaType) {
        if (mediaType == null || !mediaType.getType().equalsIgnoreCase("image")) {
            throw new IllegalArgumentException("이미지 MediaType이 필요합니다.");
        }

        String subtype = mediaType.getSubtype().toLowerCase(Locale.ROOT);
        if (subtype.equals("png")) {
            return "png";
        }
        if (subtype.equals("jpeg")) {
            return "jpg";
        }
        if (subtype.equals("webp")) {
            return "webp";
        }
        throw new IllegalArgumentException("지원하지 않는 이미지 MediaType입니다: " + mediaType);
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null) {
            throw new IllegalArgumentException("S3 생성 이미지 prefix가 필요합니다.");
        }

        String normalized = prefix.strip().replace('\\', '/');
        normalized = StringUtils.trimLeadingCharacter(normalized, '/');
        normalized = StringUtils.trimTrailingCharacter(normalized, '/');
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("S3 생성 이미지 prefix가 필요합니다.");
        }
        return normalized;
    }
}
