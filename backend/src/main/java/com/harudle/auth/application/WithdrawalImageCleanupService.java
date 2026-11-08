package com.harudle.auth.application;

import com.harudle.auth.domain.WithdrawalImageDeletion;
import com.harudle.auth.infrastructure.S3ProfileImageStorage;
import com.harudle.auth.infrastructure.WithdrawalImageRepository;
import com.harudle.auth.infrastructure.WithdrawalImageRepository.ImageTargets;
import com.harudle.generation.diary.service.port.BackupObjectStorage;
import com.harudle.generation.diary.service.port.ImageStorage;
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
    private final WithdrawalImageRepository images;

    public WithdrawalImageCleanupService(
            ObjectProvider<ImageStorage> diaryStorage,
            ObjectProvider<S3ProfileImageStorage> profileStorage,
            ObjectProvider<BackupObjectStorage> backupStorage,
            WithdrawalImageRepository images
    ) {
        this.diaryStorage = diaryStorage;
        this.profileStorage = profileStorage;
        this.backupStorage = backupStorage;
        this.images = images;
    }

    public void delete(ImageTargets targets) {
        Objects.requireNonNull(targets, "삭제할 이미지 목록은 필수입니다.");
        targets.deletions().forEach(this::delete);
    }

    private void delete(WithdrawalImageDeletion task) {
        try {
            deleteFromStorage(task);
            images.removeCleanupTask(task);
        } catch (RuntimeException exception) {
            LOGGER.warn("event=withdrawal_image_delete_failed storage={} exceptionType={}",
                    task.storage(), exception.getClass().getSimpleName());
        }
    }

    private void deleteFromStorage(WithdrawalImageDeletion task) {
        switch (task.storage()) {
            case S3_DIARY -> requireDiaryStorage().delete(task.objectKey());
            case S3_PROFILE -> requireProfileStorage().delete(task.objectKey());
            case R2 -> requireBackupStorage().delete(task.objectKey());
        }
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
}
