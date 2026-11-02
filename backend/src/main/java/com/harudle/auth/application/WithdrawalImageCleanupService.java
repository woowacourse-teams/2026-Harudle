package com.harudle.auth.application;

import com.harudle.auth.infrastructure.S3ProfileImageStorage;
import com.harudle.auth.infrastructure.WithdrawalImageRepository.ImageTargets;
import com.harudle.generation.diary.domain.ImageVariantKeys;
import com.harudle.generation.diary.service.port.BackupObjectStorage;
import com.harudle.generation.diary.service.port.ImageStorage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class WithdrawalImageCleanupService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WithdrawalImageCleanupService.class);

    private final ObjectProvider<ImageStorage> diaryStorage;
    private final ObjectProvider<S3ProfileImageStorage> profileStorage;
    private final ObjectProvider<BackupObjectStorage> backupStorage;

    public WithdrawalImageCleanupService(
            ObjectProvider<ImageStorage> diaryStorage,
            ObjectProvider<S3ProfileImageStorage> profileStorage,
            ObjectProvider<BackupObjectStorage> backupStorage
    ) {
        this.diaryStorage = diaryStorage;
        this.profileStorage = profileStorage;
        this.backupStorage = backupStorage;
    }

    public List<DeletionFailure> delete(ImageTargets targets) {
        Objects.requireNonNull(targets, "삭제할 이미지 목록은 필수입니다.");
        List<DeletionFailure> failures = new ArrayList<>();
        for (String key : targets.diaryImages()) {
            delete("S3_DIARY", () -> requireDiaryStorage().delete(key), failures);
            deleteBackups(key, failures);
        }
        for (String key : targets.profileImages()) {
            delete("S3_PROFILE", () -> requireProfileStorage().delete(key), failures);
        }
        return List.copyOf(failures);
    }

    private void deleteBackups(String imageKey, List<DeletionFailure> failures) {
        List<String> originalKeys;
        try {
            originalKeys = ImageVariantKeys.originalImageKeyCandidatesForBackup(imageKey);
        } catch (IllegalArgumentException exception) {
            recordFailure("R2", exception, failures);
            return;
        }
        for (String key : originalKeys) {
            delete("R2", () -> requireBackupStorage().delete(key), failures);
        }
    }

    private void delete(String storage, Runnable action, List<DeletionFailure> failures) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            recordFailure(storage, exception, failures);
        }
    }

    private void recordFailure(String storage, RuntimeException exception, List<DeletionFailure> failures) {
        String exceptionType = exception.getClass().getSimpleName();
        failures.add(new DeletionFailure(storage, exceptionType));
        LOGGER.warn("event=withdrawal_image_delete_failed storage={} exceptionType={}", storage, exceptionType);
    }

    private ImageStorage requireDiaryStorage() {
        ImageStorage storage = diaryStorage.getIfAvailable();
        if (storage == null) {
            throw new IllegalStateException("일기 이미지 저장소가 구성되지 않았습니다.");
        }
        return storage;
    }

    private S3ProfileImageStorage requireProfileStorage() {
        S3ProfileImageStorage storage = profileStorage.getIfAvailable();
        if (storage == null) {
            throw new IllegalStateException("프로필 이미지 저장소가 구성되지 않았습니다.");
        }
        return storage;
    }

    private BackupObjectStorage requireBackupStorage() {
        BackupObjectStorage storage = backupStorage.getIfAvailable();
        if (storage == null) {
            throw new IllegalStateException("백업 이미지 저장소가 구성되지 않았습니다.");
        }
        return storage;
    }

    public record DeletionFailure(String storage, String exceptionType) {}
}
