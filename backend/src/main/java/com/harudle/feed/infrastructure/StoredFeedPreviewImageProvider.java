package com.harudle.feed.infrastructure;

import com.harudle.feed.service.port.FeedPreviewImageProvider;
import com.harudle.feed.service.port.FeedPreviewImageRenderer;
import com.harudle.generation.diary.domain.ImageVariantKeys;
import com.harudle.generation.diary.service.port.BackupObjectStorage;
import com.harudle.generation.diary.service.port.BackupStorageException;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** 후보의 다운로드와 JPEG 변환이 모두 성공해야 선택한다. 저장소에는 쓰지 않는다. */
@Component
public class StoredFeedPreviewImageProvider implements FeedPreviewImageProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(StoredFeedPreviewImageProvider.class);
    private static final Set<String> SUPPORTED_SUBTYPES = Set.of("png", "jpeg", "webp");
    private final ObjectProvider<ImageStorage> primaryStorages;
    private final ObjectProvider<BackupObjectStorage> backups;
    private final FeedPreviewImageRenderer renderer;

    public StoredFeedPreviewImageProvider(ObjectProvider<ImageStorage> primaryStorages,
            ObjectProvider<BackupObjectStorage> backups, FeedPreviewImageRenderer renderer) {
        this.primaryStorages = Objects.requireNonNull(primaryStorages);
        this.backups = Objects.requireNonNull(backups);
        this.renderer = Objects.requireNonNull(renderer);
    }

    @Override
    public ReferenceImage provide(String imageObjectKey) {
        Objects.requireNonNull(imageObjectKey, "미리보기 이미지 저장 키는 필수입니다.");
        if (imageObjectKey.isBlank()) {
            throw new IllegalArgumentException("미리보기 이미지 저장 키는 비어 있을 수 없습니다.");
        }
        abortIfInterrupted(Source.ALL, Stage.PROVIDE, null);
        List<String> originals = originalCandidates(imageObjectKey);
        ImageStorage primary = primaryStorages.getIfAvailable();
        if (primary != null) {
            List<String> candidates = new ArrayList<>(originals);
            if (!candidates.contains(imageObjectKey)) {
                candidates.add(imageObjectKey);
            }
            for (String candidate : candidates) {
                Optional<ReferenceImage> preview = fromPrimary(primary, candidate);
                if (preview.isPresent()) {
                    return preview.orElseThrow();
                }
            }
        }
        abortIfInterrupted(Source.R2, Stage.LOAD, null);
        BackupObjectStorage backup = backups.getIfAvailable();
        if (backup != null) {
            for (String candidate : originals) {
                Optional<ReferenceImage> preview = fromBackup(backup, candidate);
                if (preview.isPresent()) {
                    return preview.orElseThrow();
                }
            }
        }
        diagnostic(Source.ALL, Stage.PROVIDE, "no_usable_candidate");
        throw unavailable();
    }

    private Optional<ReferenceImage> fromPrimary(ImageStorage storage, String key) {
        abortIfInterrupted(Source.S3, Stage.PRESENCE, null);
        Stage stage = Stage.PRESENCE;
        ReferenceImage original;
        try {
            if (!storage.exists(key)) {
                return Optional.empty();
            }
            stage = Stage.LOAD;
            abortIfInterrupted(Source.S3, stage, null);
            original = storage.load(key);
        } catch (ImageStorageException failure) {
            abortIfInterrupted(Source.S3, stage, failure);
            diagnostic(Source.S3, stage, "storage_failed");
            return Optional.empty();
        }
        return render(Source.S3, original);
    }

    private Optional<ReferenceImage> fromBackup(BackupObjectStorage storage, String key) {
        abortIfInterrupted(Source.R2, Stage.LOAD, null);
        Optional<ReferenceImage> original;
        try {
            original = storage.download(key);
        } catch (BackupStorageException | ImageStorageException failure) {
            abortIfInterrupted(Source.R2, Stage.LOAD, failure);
            diagnostic(Source.R2, Stage.LOAD, "storage_failed");
            return Optional.empty();
        }
        return original.flatMap(image -> render(Source.R2, image));
    }

    private Optional<ReferenceImage> render(Source source, ReferenceImage original) {
        abortIfInterrupted(source, Stage.RENDER, null);
        if (!"image".equalsIgnoreCase(original.mediaType().getType())
                || !SUPPORTED_SUBTYPES.contains(original.mediaType().getSubtype().toLowerCase(Locale.ROOT))) {
            diagnostic(source, Stage.RENDER, "unsupported_type");
            return Optional.empty();
        }
        ReferenceImage preview;
        try {
            preview = renderer.render(original);
        } catch (ImageStorageException failure) {
            abortIfInterrupted(source, Stage.RENDER, failure);
            diagnostic(source, Stage.RENDER, "render_failed");
            return Optional.empty();
        }
        abortIfInterrupted(source, Stage.RENDER, null);
        if (!MediaType.IMAGE_JPEG.isCompatibleWith(preview.mediaType())) {
            throw new IllegalStateException("미리보기 렌더러는 JPEG를 반환해야 합니다.");
        }
        return Optional.of(preview);
    }

    private static void abortIfInterrupted(Source source, Stage stage, @Nullable Throwable failure) {
        boolean interrupted = Thread.currentThread().isInterrupted();
        Throwable cause = failure;
        for (int depth = 0; !interrupted && cause != null && depth < 16; depth++, cause = cause.getCause()) {
            interrupted = cause instanceof InterruptedException
                    || (cause instanceof InterruptedIOException && !(cause instanceof SocketTimeoutException));
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
            diagnostic(source, stage, "interrupted");
            throw unavailable();
        }
    }

    private static List<String> originalCandidates(String key) {
        try {
            return ImageVariantKeys.originalImageKeyCandidatesForLookup(key);
        } catch (IllegalArgumentException ignored) {
            // 최적화 도입 전의 키도 동일한 저장소 경로 검증을 거쳐 조회한다.
            return List.of(key);
        }
    }

    private static void diagnostic(Source source, Stage stage, String reason) {
        LOGGER.atWarn().addKeyValue("event", "feed_preview_candidate_failed")
                .addKeyValue("source", source.name()).addKeyValue("stage", stage.name())
                .addKeyValue("reason", reason)
                .log("event=feed_preview_candidate_failed source={} stage={} reason={}", source, stage, reason);
    }

    private static ImageStorageException unavailable() {
        return new ImageStorageException("공유 미리보기 이미지를 읽을 수 없습니다.");
    }

    private enum Source { S3, R2, ALL }

    private enum Stage { PRESENCE, LOAD, RENDER, PROVIDE }
}
