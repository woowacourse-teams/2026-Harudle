package com.harudle.generation.diary.service;

import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.port.ImageStorage;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DiscardedGenerationImageCleaner {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscardedGenerationImageCleaner.class);
    private final DiaryGenerationRepository diaryGenerationRepository;
    private final ImageStorage imageStorage;

    DiscardedGenerationImageCleaner(DiaryGenerationRepository diaryGenerationRepository, ImageStorage imageStorage) {
        this.diaryGenerationRepository = diaryGenerationRepository;
        this.imageStorage = imageStorage;
    }

    void deleteIfUnused(DiaryGeneration generation, String imageObjectKey) {
        if (generation.notUsesImageObjectKey(imageObjectKey)) {
            deleteDiscardedImage(imageObjectKey);
        }
    }

    void deleteIfSafelyDiscardable(UUID generationId, String imageObjectKey, RuntimeException completionException) {
        try {
            boolean deletable = diaryGenerationRepository.findById(generationId)
                    .map(generation -> canDeleteImage(generation, imageObjectKey))
                    .orElse(true);
            if (deletable) {
                deleteDiscardedImage(imageObjectKey);
            }
        } catch (RuntimeException verificationException) {
            if (verificationException != completionException) {
                completionException.addSuppressed(verificationException);
            }
            LOGGER.warn(
                    "생성 완료 상태를 확인하지 못해 이미지 삭제를 보류합니다. generationId={}, objectKey={}",
                    generationId,
                    imageObjectKey,
                    verificationException
            );
        }
    }

    void deleteDiscardedImage(String imageObjectKey) {
        try {
            imageStorage.delete(imageObjectKey);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "완료되지 못한 생성 이미지 삭제에 실패했습니다. objectKey={}",
                    imageObjectKey,
                    exception
            );
        }
    }

    private static boolean canDeleteImage(DiaryGeneration generation, String imageObjectKey) {
        return switch (generation.getStatus()) {
            case PROCESSING -> false;
            case FAILED -> true;
            case SUCCEEDED -> generation.notUsesImageObjectKey(imageObjectKey);
        };
    }
}
