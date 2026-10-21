package com.harudle.auth.application;

import com.harudle.auth.infrastructure.S3ProfileImageStorage;
import java.io.IOException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProfileImageUploadService {

    private static final int MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024;

    private final ProfileImageProcessor imageProcessor;
    private final ProfileImageChangeService imageChangeService;
    private final ProfileChangeService profileChangeService;
    private final ProfileImageCleanupService cleanupService;
    private final ObjectProvider<S3ProfileImageStorage> storageProvider;

    public ProfileImageUploadService(
            ProfileImageProcessor imageProcessor,
            ProfileImageChangeService imageChangeService,
            ProfileChangeService profileChangeService,
            ProfileImageCleanupService cleanupService,
            ObjectProvider<S3ProfileImageStorage> storageProvider
    ) {
        this.imageProcessor = imageProcessor;
        this.imageChangeService = imageChangeService;
        this.profileChangeService = profileChangeService;
        this.cleanupService = cleanupService;
        this.storageProvider = storageProvider;
    }

    public void upload(UUID userId, MultipartFile image) {
        upload(userId, image, null);
    }

    public void changeProfile(
            UUID userId,
            @Nullable String nickname,
            @Nullable Integer imageCode,
            @Nullable MultipartFile image
    ) {
        if (imageCode != null && image != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "기본 이미지와 개인 사진은 함께 선택할 수 없습니다.");
        }
        if (nickname == null && imageCode == null && image == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "변경할 프로필 정보를 보내 주세요.");
        }
        if (image != null) {
            upload(userId, image, nickname);
            return;
        }

        String previousObjectKey = profileChangeService.change(userId, nickname, imageCode, null);
        if (imageCode != null) {
            cleanupService.clean(userId, previousObjectKey);
        }
    }

    private void upload(UUID userId, MultipartFile image, @Nullable String nickname) {
        imageChangeService.validateUser(userId);

        byte[] webpBytes = imageProcessor.process(readImage(image));
        S3ProfileImageStorage storage = requireStorage();
        String objectKey = store(storage, userId, webpBytes);

        try {
            String previousObjectKey = profileChangeService.change(userId, nickname, null, objectKey);
            cleanupService.clean(userId, previousObjectKey);
        } catch (RuntimeException exception) {
            cleanupService.clean(userId, objectKey);
            throw exception;
        }
    }

    public void changeDefault(UUID userId, int imageCode) {
        String previousObjectKey = imageChangeService.changeDefault(userId, imageCode);
        cleanupService.clean(userId, previousObjectKey);
    }

    private String store(S3ProfileImageStorage storage, UUID userId, byte[] webpBytes) {
        try {
            return storage.store(userId, webpBytes);
        } catch (ProfileImageStorageException exception) {
            // S3 요청이 뒤늦게 완료될 수 있어 유예 후 다음 프로필 변경 때 확인한다.
            cleanupService.defer(userId, exception.getObjectKey());
            throw exception;
        }
    }

    private byte[] readImage(MultipartFile image) {
        if (image.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "프로필 이미지 파일은 필수입니다.");
        }
        if (image.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "프로필 이미지는 5MiB 이하여야 합니다.");
        }

        try (var input = image.getInputStream()) {
            return input.readNBytes(MAX_FILE_SIZE_BYTES + 1);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "프로필 이미지 파일을 읽을 수 없습니다.", exception);
        }
    }

    private S3ProfileImageStorage requireStorage() {
        S3ProfileImageStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            throw new ProfileImageStorageException("프로필 이미지 저장소가 구성되지 않았습니다.", null, null);
        }
        return storage;
    }
}
