package com.harudle.generation.diary.service;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.harudle.generation.config.R2StorageProperties;
import com.harudle.generation.config.S3StorageProperties;
import com.harudle.generation.diary.domain.ImageObjectKeyPolicy;
import com.harudle.generation.diary.domain.ImageVariantKeys;
import com.harudle.generation.diary.service.dto.ImageBackupResult;
import com.harudle.generation.diary.service.exception.ImageBackupException;
import com.harudle.generation.diary.service.exception.ImageBackupException.Reason;
import com.harudle.generation.diary.service.port.BackupObjectStorage;
import com.harudle.generation.diary.service.port.BackupStorageException;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.dto.BackupUploadResult;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;

public final class ImageBackupService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImageBackupService.class);

    private final ImageStorage sourceStorage;
    private final BackupObjectStorage backupStorage;
    private final S3StorageProperties sourceProperties;
    private final R2StorageProperties backupProperties;
    private final String generatedRoot;
    private final int maxObjectSizeBytes;
    private final Clock clock;

    public ImageBackupService(
            ImageStorage sourceStorage,
            BackupObjectStorage backupStorage,
            S3StorageProperties sourceProperties,
            R2StorageProperties backupProperties,
            Clock clock
    ) {
        this.sourceStorage = Objects.requireNonNull(sourceStorage);
        this.backupStorage = Objects.requireNonNull(backupStorage);
        this.sourceProperties = Objects.requireNonNull(sourceProperties);
        this.backupProperties = Objects.requireNonNull(backupProperties);
        this.clock = Objects.requireNonNull(clock);
        if (!sourceProperties.isEnvironmentMatched()
                || !sourceProperties.environment().equals(backupProperties.environment())) {
            throw new IllegalArgumentException("이미지 백업의 S3와 R2 실행 환경이 일치해야 합니다.");
        }
        if (!sourceProperties.isMaxObjectSizePositive() || !backupProperties.isMaxObjectSizeValid()) {
            throw new IllegalArgumentException("유효한 이미지 백업 최대 크기가 필요합니다.");
        }
        this.generatedRoot = sourceProperties.generatedPrefix() + "/";
        this.maxObjectSizeBytes = Math.toIntExact(Math.min(
                sourceProperties.maxObjectSize().toBytes(), backupProperties.maxObjectSize().toBytes()
        ));
    }

    /** 원본이 없으면 empty, 검증이 성공하면 결과를 반환하며 나머지 실패는 예외로 전달한다. */
    public Optional<ImageBackupResult> backup(String imageObjectKey) {
        BackupAudit audit = new BackupAudit();
        try {
            validateKey(imageObjectKey);
            List<String> candidates = ImageVariantKeys.originalImageKeyCandidatesForBackup(imageObjectKey);
            audit.representativeKey = imageObjectKey;

            audit.stage = Stage.FIND_SOURCE;
            Optional<String> originalKey = candidates.stream().filter(sourceStorage::exists).findFirst();
            if (originalKey.isEmpty()) {
                audit.result = "ORIGINAL_NOT_FOUND";
                return Optional.empty();
            }
            audit.originalKey = originalKey.orElseThrow();

            audit.stage = Stage.READ_SOURCE;
            Snapshot source = snapshot(sourceStorage.load(audit.originalKey));
            audit.source = source.fingerprint();

            audit.stage = Stage.UPLOAD_BACKUP;
            audit.uploadResult = Objects.requireNonNull(backupStorage.uploadIfAbsent(audit.originalKey,
                    new GeneratedImage(new ByteArrayResource(source.bytes()), source.fingerprint().mediaType())));

            audit.stage = Stage.READ_BACKUP;
            ReferenceImage backedUp = backupStorage.download(audit.originalKey)
                    .orElseThrow(() -> new ImageBackupException(Reason.BACKUP_NOT_FOUND));
            audit.backup = snapshot(backedUp).fingerprint();

            audit.stage = Stage.VERIFY_BACKUP;
            audit.mimeMatches = audit.source.mediaType().equals(audit.backup.mediaType());
            audit.sizeMatches = audit.source.size() == audit.backup.size();
            audit.sha256Matches = audit.source.sha256().equals(audit.backup.sha256());
            if (!audit.mimeMatches || !audit.sizeMatches || !audit.sha256Matches) {
                throw new ImageBackupException(Reason.VERIFICATION_FAILED);
            }
            audit.verifiedAt = clock.instant();
            ImageBackupResult result = new ImageBackupResult(audit.originalKey, audit.uploadResult,
                    audit.source.mediaType(), audit.source.size(), audit.source.sha256(), audit.verifiedAt);
            audit.result = "VERIFIED";
            return Optional.of(result);
        } catch (RuntimeException exception) {
            audit.failureType = failureType(exception, audit.stage);
            throw exception;
        } finally {
            logResult(audit);
        }
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank() || key.getBytes(UTF_8).length > ImageObjectKeyPolicy.MAX_UTF8_BYTES
                || key.contains("\\") || key.contains("%") || key.chars().anyMatch(Character::isISOControl)
                || Arrays.stream(key.split("/", -1)).anyMatch(segment ->
                        segment.isBlank() || segment.equals(".") || segment.equals(".."))
                || !key.startsWith(generatedRoot)) {
            throw new IllegalArgumentException("현재 환경의 유효한 생성 이미지 키가 필요합니다.");
        }
    }

    private Snapshot snapshot(ReferenceImage image) {
        try (InputStream stream = image.resource().getInputStream()) {
            byte[] bytes = stream.readNBytes(maxObjectSizeBytes + 1);
            if (bytes.length == 0 || bytes.length > maxObjectSizeBytes) {
                throw new ImageBackupException(Reason.INVALID_CONTENT_SIZE);
            }
            return new Snapshot(bytes, new Fingerprint(image.mediaType(), bytes.length, sha256(bytes)));
        } catch (IOException exception) {
            throw new ImageBackupException(Reason.CONTENT_READ_FAILED, exception);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 계산기를 사용할 수 없습니다.", exception);
        }
    }

    private String failureType(RuntimeException exception, Stage stage) {
        if (exception instanceof BackupStorageException storageException) {
            return storageException.failureType().name();
        }
        if (exception instanceof ImageStorageException storageException) {
            return storageException.diagnosticType() == null ? "OTHER" : storageException.diagnosticType().name();
        }
        if (exception instanceof ImageBackupException backupException) {
            return backupException.reason().name();
        }
        if (exception instanceof IllegalArgumentException && stage == Stage.VALIDATE_KEY) {
            return "REQUEST_VALIDATION_ERROR";
        }
        return "OTHER";
    }

    private void logResult(BackupAudit audit) {
        LoggingEventBuilder event = "VERIFIED".equals(audit.result) ? LOGGER.atInfo() : LOGGER.atWarn();
        event.addKeyValue("event", "image_backup_completed")
                .addKeyValue("attemptId", audit.attemptId)
                .addKeyValue("representativeKey", audit.representativeKey)
                .addKeyValue("s3Bucket", sourceProperties.bucket())
                .addKeyValue("originalKey", field(audit.originalKey))
                .addKeyValue("r2Bucket", backupProperties.bucket())
                .addKeyValue("r2Key", field(audit.originalKey))
                .addKeyValue("stage", audit.stage.name())
                .addKeyValue("result", audit.result)
                .addKeyValue("uploadResult", field(audit.uploadResult))
                .addKeyValue("sourceMime", mime(audit.source))
                .addKeyValue("backupMime", mime(audit.backup))
                .addKeyValue("sourceSize", audit.source == null ? "none" : audit.source.size())
                .addKeyValue("backupSize", audit.backup == null ? "none" : audit.backup.size())
                .addKeyValue("sourceSha256", audit.source == null ? "none" : audit.source.sha256())
                .addKeyValue("backupSha256", audit.backup == null ? "none" : audit.backup.sha256())
                .addKeyValue("mimeMatches", field(audit.mimeMatches))
                .addKeyValue("sizeMatches", field(audit.sizeMatches))
                .addKeyValue("sha256Matches", field(audit.sha256Matches))
                .addKeyValue("failureType", audit.failureType)
                .addKeyValue("verifiedAt", field(audit.verifiedAt))
                .addKeyValue("completedAt", clock.instant())
                .log("event=image_backup_completed attemptId={} representativeKey={} originalKey={} "
                                + "stage={} result={} uploadResult={} failureType={}",
                        audit.attemptId, audit.representativeKey, field(audit.originalKey),
                        audit.stage.name(), audit.result, field(audit.uploadResult), audit.failureType);
    }

    private Object field(@Nullable Object value) {
        return value == null ? "none" : value;
    }

    private String mime(@Nullable Fingerprint fingerprint) {
        return fingerprint == null ? "none"
                : fingerprint.mediaType().getType() + "/" + fingerprint.mediaType().getSubtype();
    }

    private record Fingerprint(MediaType mediaType, long size, String sha256) {
    }

    private record Snapshot(byte[] bytes, Fingerprint fingerprint) {
    }

    private enum Stage {
        VALIDATE_KEY, FIND_SOURCE, READ_SOURCE, UPLOAD_BACKUP, READ_BACKUP, VERIFY_BACKUP
    }

    private static final class BackupAudit {

        private final UUID attemptId = UUID.randomUUID();
        private String representativeKey = "invalid";
        private @Nullable String originalKey;
        private Stage stage = Stage.VALIDATE_KEY;
        private String result = "FAILED";
        private String failureType = "none";
        private @Nullable BackupUploadResult uploadResult;
        private @Nullable Fingerprint source;
        private @Nullable Fingerprint backup;
        private @Nullable Boolean mimeMatches;
        private @Nullable Boolean sizeMatches;
        private @Nullable Boolean sha256Matches;
        private @Nullable Instant verifiedAt;
    }
}
