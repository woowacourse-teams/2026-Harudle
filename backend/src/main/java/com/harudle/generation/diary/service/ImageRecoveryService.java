package com.harudle.generation.diary.service;

import static com.harudle.generation.diary.service.exception.ImageRecoveryException.Reason.*;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.harudle.generation.config.R2StorageProperties;
import com.harudle.generation.config.S3StorageProperties;
import com.harudle.generation.diary.domain.ImageObjectKeyPolicy;
import com.harudle.generation.diary.domain.ImageVariantKeys;
import com.harudle.generation.diary.service.dto.ImageRecoveryResult;
import com.harudle.generation.diary.service.exception.ImageRecoveryException;
import com.harudle.generation.diary.service.port.BackupObjectStorage;
import com.harudle.generation.diary.service.port.BackupStorageException;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.dto.BackupObjectMetadata;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** R2 원본을 검증하고, 현재 환경의 누락 객체만 조건부로 복구한다. */
public final class ImageRecoveryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImageRecoveryService.class);
    private final ImageStorage storage;
    private final BackupObjectStorage backup;
    private final S3StorageProperties s3;
    private final R2StorageProperties r2;
    private final Clock clock;
    private final int maxBytes;

    public ImageRecoveryService(ImageStorage storage, BackupObjectStorage backup,
            S3StorageProperties s3, R2StorageProperties r2, Clock clock) {
        this.storage = Objects.requireNonNull(storage);
        this.backup = Objects.requireNonNull(backup);
        this.s3 = Objects.requireNonNull(s3);
        this.r2 = Objects.requireNonNull(r2);
        this.clock = Objects.requireNonNull(clock);
        if (!s3.isEnvironmentMatched() || !s3.environment().equals(r2.environment())
                || !s3.isMaxObjectSizePositive() || !r2.isMaxObjectSizeValid()) {
            throw new IllegalArgumentException("복구 저장소의 환경과 최대 크기가 유효해야 합니다.");
        }
        this.maxBytes = Math.toIntExact(Math.min(s3.maxObjectSize().toBytes(), r2.maxObjectSize().toBytes()));
    }

    public String environment() {
        return s3.environment();
    }

    public ImageRecoveryResult recover(String imageObjectKey, boolean dryRun) {
        String attemptId = UUID.randomUUID().toString();
        String loggedKey = "invalid";
        String originalKey = "none";
        String sha256 = "none";
        String mime = "none";
        long size = 0;
        String status = "FAILED";
        String stage = "VALIDATE_KEY";
        String failure = "none";
        List<String> missing = new ArrayList<>();
        boolean originalRestored = false;
        boolean variantsRestored = false;
        try {
            validateKey(imageObjectKey);
            List<String> candidates = ImageVariantKeys.originalImageKeyCandidatesForBackup(imageObjectKey);
            loggedKey = imageObjectKey;
            stage = "READ_BACKUP";
            List<BackupObjectMetadata> matches = candidates.stream()
                    .map(backup::findMetadata).flatMap(java.util.Optional::stream).toList();
            if (matches.isEmpty()) {
                throw new ImageRecoveryException(BACKUP_NOT_FOUND);
            }
            if (matches.size() != 1) {
                throw new ImageRecoveryException(AMBIGUOUS_BACKUP);
            }
            BackupObjectMetadata metadata = matches.getFirst();
            originalKey = metadata.objectKey();
            if (!candidates.contains(originalKey)) {
                throw new ImageRecoveryException(INVALID_CONTENT);
            }
            Snapshot original = snapshot(backup.download(originalKey)
                    .orElseThrow(() -> new ImageRecoveryException(BACKUP_NOT_FOUND)));
            sha256 = original.sha256();
            mime = original.mime().getType() + "/" + original.mime().getSubtype();
            size = original.bytes().length;
            if (metadata.size() != original.bytes().length || !metadata.mediaType().equals(original.mime())) {
                throw new ImageRecoveryException(BACKUP_CHANGED);
            }
            validateOriginal(originalKey, original);

            stage = "CHECK_DESTINATION";
            List<String> targets = targets(imageObjectKey, originalKey);
            for (String target : targets) {
                if (!storage.exists(target)) {
                    missing.add(target);
                }
            }
            // 쓰기 전 모든 대상의 조회를 마치고 기존 원본과 백업의 충돌도 확인한다.
            if (!missing.contains(originalKey)) {
                verifyOriginal(original, snapshot(storage.load(originalKey)), ORIGINAL_CONFLICT);
            }
            if (missing.isEmpty() || dryRun) {
                status = missing.isEmpty() ? "ALREADY_EXISTS" : "WOULD_RESTORE";
            } else {
                GeneratedImage image = new GeneratedImage(new ByteArrayResource(original.bytes()), original.mime());
                stage = "RESTORE_ORIGINAL";
                if (missing.contains(originalKey)) {
                    originalRestored = storage.restoreIfMissing(originalKey, image);
                }
                // 412 경합도 읽어서 검증한다. 다른 원본이면 파생 이미지 생성 전에 중단한다.
                stage = "VERIFY_ORIGINAL";
                verifyOriginal(original, snapshot(storage.load(originalKey)), ORIGINAL_CONFLICT);
                if (ImageVariantKeys.isOptimizedDetailKey(imageObjectKey)
                        && missing.stream().anyMatch(key -> !key.equals(metadata.objectKey()))) {
                    stage = "RESTORE_VARIANTS";
                    variantsRestored = storage.restoreOptimizedIfMissing(imageObjectKey, image);
                }
                stage = "VERIFY_DESTINATION";
                verifyOriginal(original, snapshot(storage.load(originalKey)), VERIFICATION_FAILED);
                for (String target : targets) {
                    if (!storage.exists(target)) {
                        throw new ImageRecoveryException(VERIFICATION_FAILED);
                    }
                }
                status = originalRestored || variantsRestored ? "RESTORED" : "ALREADY_EXISTS";
            }
            return new ImageRecoveryResult(status, s3.environment(), s3.bucket(), r2.bucket(), imageObjectKey,
                    originalKey, original.mime().toString(), original.bytes().length, sha256, missing,
                    originalRestored, variantsRestored, clock.instant());
        } catch (RuntimeException exception) {
            failure = failureCode(exception);
            throw exception;
        } finally {
            var event = "FAILED".equals(status) ? LOGGER.atWarn() : LOGGER.atInfo();
            event.addKeyValue("event", "image_r2_recovery_completed")
                    .addKeyValue("attemptId", attemptId).addKeyValue("environment", s3.environment())
                    .addKeyValue("representativeKey", loggedKey).addKeyValue("originalKey", originalKey)
                    .addKeyValue("stage", stage).addKeyValue("status", status).addKeyValue("failureCode", failure)
                    .addKeyValue("dryRun", dryRun).addKeyValue("s3Bucket", s3.bucket())
                    .addKeyValue("r2Bucket", r2.bucket()).addKeyValue("sha256", sha256)
                    .addKeyValue("mime", mime).addKeyValue("size", size).addKeyValue("completedAt", clock.instant())
                    .addKeyValue("missingKeys", missing).addKeyValue("originalRestored", originalRestored)
                    .addKeyValue("variantsRestored", variantsRestored)
                    .log("event=image_r2_recovery_completed attemptId={} representativeKey={} originalKey={} "
                            + "dryRun={} stage={} status={} failureCode={}",
                            attemptId, loggedKey, originalKey, dryRun, stage, status, failure);
        }
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank() || key.getBytes(UTF_8).length > ImageObjectKeyPolicy.MAX_UTF8_BYTES
                || !key.startsWith(s3.generatedPrefix() + "/") || key.contains("\\") || key.contains("%")
                || key.chars().anyMatch(Character::isISOControl)
                || Arrays.stream(key.split("/", -1)).anyMatch(part ->
                        part.isBlank() || part.equals(".") || part.equals(".."))) {
            throw new IllegalArgumentException("현재 환경의 생성 이미지 키가 필요합니다.");
        }
    }

    private List<String> targets(String key, String originalKey) {
        if (!ImageVariantKeys.isOptimizedDetailKey(key)) {
            return List.of(originalKey);
        }
        return List.of(originalKey, key, ImageVariantKeys.toThumbnailKeyIfOptimizedDetail(key));
    }

    private Snapshot snapshot(ReferenceImage image) {
        try (InputStream stream = image.resource().getInputStream()) {
            byte[] bytes = stream.readNBytes(maxBytes + 1);
            if (bytes.length == 0 || bytes.length > maxBytes) {
                throw new ImageRecoveryException(INVALID_CONTENT);
            }
            return new Snapshot(bytes, image.mediaType(),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (IOException exception) {
            throw new ImageRecoveryException(CONTENT_READ_FAILED, exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 계산기를 사용할 수 없습니다.", exception);
        }
    }

    private void validateOriginal(String key, Snapshot image) {
        if (image.mime().isWildcardType() || image.mime().isWildcardSubtype()) {
            throw new ImageRecoveryException(INVALID_CONTENT);
        }
        byte[] bytes = image.bytes();
        boolean valid = switch (key.substring(key.lastIndexOf('/') + 1)) {
            case "image.png" -> MediaType.IMAGE_PNG.isCompatibleWith(image.mime()) && bytes.length >= 8
                    && Arrays.equals(Arrays.copyOf(bytes, 8), new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
            case "image.jpg" -> MediaType.IMAGE_JPEG.isCompatibleWith(image.mime()) && bytes.length >= 3
                    && bytes[0] == (byte) 255 && bytes[1] == (byte) 216 && bytes[2] == (byte) 255;
            case "image.webp" -> MediaType.parseMediaType("image/webp").isCompatibleWith(image.mime()) && bytes.length >= 12
                    && "RIFF".equals(new String(bytes, 0, 4, UTF_8))
                    && "WEBP".equals(new String(bytes, 8, 4, UTF_8));
            default -> false;
        };
        if (!valid) {
            throw new ImageRecoveryException(INVALID_CONTENT);
        }
    }

    private void verifyOriginal(Snapshot expected, Snapshot actual, ImageRecoveryException.Reason reason) {
        if (!expected.mime().equals(actual.mime()) || expected.bytes().length != actual.bytes().length
                || !expected.sha256().equals(actual.sha256())) {
            throw new ImageRecoveryException(reason);
        }
    }

    public static String failureCode(RuntimeException exception) {
        if (exception instanceof ImageRecoveryException recovery) {
            return recovery.reason().name();
        }
        if (exception instanceof BackupStorageException storage) {
            return "R2_" + storage.failureType().name();
        }
        if (exception instanceof ImageStorageException storage) {
            return "S3_" + (storage.diagnosticType() == null ? "ERROR" : storage.diagnosticType().name());
        }
        return exception instanceof IllegalArgumentException ? "INVALID_IMAGE_KEY" : "OTHER";
    }

    private record Snapshot(byte[] bytes, MediaType mime, String sha256) {
    }
}
