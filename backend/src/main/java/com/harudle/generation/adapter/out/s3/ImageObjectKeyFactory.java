package com.harudle.generation.adapter.out.s3;

import com.harudle.generation.config.S3StorageProperties;
import com.harudle.generation.diary.domain.ImageVariantKeys;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;

public final class ImageObjectKeyFactory {

    private final String generatedPrefix;
    private final Pattern generatedKeyPattern;

    public ImageObjectKeyFactory(S3StorageProperties properties) {
        Objects.requireNonNull(properties, "S3 저장소 설정이 필요합니다.");
        this.generatedPrefix = normalizePrefix(properties.generatedPrefix());
        String uuid = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
        String imageFilename = "(?:image\\.(?:png|jpg|webp)|"
                + Pattern.quote(ImageVariantKeys.DETAIL_FILENAME) + "|"
                + Pattern.quote(ImageVariantKeys.THUMBNAIL_FILENAME) + ")";
        this.generatedKeyPattern = Pattern.compile(Pattern.quote(listPrefix())
                + "(" + uuid + ")/(?:" + uuid + "/)?" + imageFilename);
    }

    public String create(UUID generationId, MediaType mediaType) {
        Objects.requireNonNull(generationId, "생성 작업 ID가 필요합니다.");
        String extension = resolveExtension(mediaType);
        return "%s/%s/%s/image.%s".formatted(generatedPrefix, generationId, UUID.randomUUID(), extension);
    }

    public String createOptimized(UUID generationId) {
        Objects.requireNonNull(generationId, "생성 작업 ID가 필요합니다.");
        return "%s/%s/%s/%s".formatted(
                generatedPrefix, generationId, UUID.randomUUID(), ImageVariantKeys.DETAIL_FILENAME
        );
    }

    String listPrefix() {
        return generatedPrefix + "/";
    }

    Optional<UUID> generationId(String objectKey) {
        if (objectKey == null) {
            return Optional.empty();
        }
        var matcher = generatedKeyPattern.matcher(objectKey);
        return matcher.matches() ? Optional.of(UUID.fromString(matcher.group(1))) : Optional.empty();
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
