package com.harudle.feed.infrastructure;

import com.harudle.feed.service.port.FeedPreviewImageReader;
import com.harudle.generation.diary.domain.ImageVariantKeys;
import com.harudle.generation.diary.service.port.BackupObjectStorage;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 공개 피드의 원본을 우선 읽고, S3가 없거나 읽지 못하면 R2 원본을 사용한다. 저장소에는 쓰지 않는다. */
@Component
public class StoredFeedPreviewImageReader implements FeedPreviewImageReader {

    private static final Set<String> SUPPORTED_SUBTYPES = Set.of("png", "jpeg", "webp");
    private final ObjectProvider<ImageStorage> primaryStorages;
    private final ObjectProvider<BackupObjectStorage> backups;

    public StoredFeedPreviewImageReader(ObjectProvider<ImageStorage> primaryStorages,
            ObjectProvider<BackupObjectStorage> backups) {
        this.primaryStorages = primaryStorages;
        this.backups = backups;
    }

    @Override
    public ReferenceImage read(String imageObjectKey) {
        try {
            List<String> originals = originalCandidates(imageObjectKey);
            Optional<ReferenceImage> primary = readPrimary(originals, imageObjectKey);
            if (primary.isPresent()) {
                return primary.orElseThrow();
            }
            BackupObjectStorage backup = backups.getIfAvailable();
            if (backup != null) {
                for (String candidate : originals) {
                    Optional<ReferenceImage> found = backup.download(candidate);
                    if (found.isPresent()) {
                        return requireSupported(found.orElseThrow());
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // 저장소 응답과 내부 경로는 외부 오류에 포함하지 않는다.
        }
        throw unavailable();
    }

    private Optional<ReferenceImage> readPrimary(List<String> originals, String imageObjectKey) {
        try {
            ImageStorage storage = primaryStorages.getIfAvailable();
            if (storage == null) {
                return Optional.empty();
            }
            List<String> candidates = new ArrayList<>(originals);
            if (!candidates.contains(imageObjectKey)) {
                candidates.add(imageObjectKey);
            }
            for (String candidate : candidates) {
                if (storage.exists(candidate)) {
                    return Optional.of(requireSupported(storage.load(candidate)));
                }
            }
        } catch (RuntimeException ignored) {
            // 존재 확인 뒤 읽기 실패도 R2 대체 대상이며, 원본 복구나 재업로드는 수행하지 않는다.
        }
        return Optional.empty();
    }

    private static ReferenceImage requireSupported(ReferenceImage image) {
        if (!"image".equalsIgnoreCase(image.mediaType().getType())
                || !SUPPORTED_SUBTYPES.contains(image.mediaType().getSubtype().toLowerCase(Locale.ROOT))) {
            throw unavailable();
        }
        return image;
    }

    private static List<String> originalCandidates(String imageObjectKey) {
        try {
            return ImageVariantKeys.originalImageKeyCandidatesForLookup(imageObjectKey);
        } catch (IllegalArgumentException ignored) {
            // 최적화 도입 전의 이미지 키는 저장소의 기존 경로 검증을 거쳐 그대로 조회한다.
            return List.of(imageObjectKey);
        }
    }

    private static ImageStorageException unavailable() {
        return new ImageStorageException("공유 미리보기 이미지를 읽을 수 없습니다.");
    }
}
