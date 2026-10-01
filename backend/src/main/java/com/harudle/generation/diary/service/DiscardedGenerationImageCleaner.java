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
            deleteDiscardedImage(generation.getId(), imageObjectKey, "completed_but_unused");
        }
    }

    void deleteIfSafelyDiscardable(UUID generationId, String imageObjectKey, RuntimeException completionException) {
        try {
            boolean deletable = diaryGenerationRepository.findById(generationId)
                    .map(generation -> canDeleteImage(generation, imageObjectKey))
                    .orElse(true);
            if (deletable) {
                deleteDiscardedImage(generationId, imageObjectKey, "completion_failed");
            }
        } catch (RuntimeException verificationException) {
            if (verificationException != completionException) {
                completionException.addSuppressed(verificationException);
            }
            LOGGER.atWarn()
                    .addKeyValue("event", "discarded_image_delete_deferred")
                    .addKeyValue("generationId", generationId.toString())
                    .addKeyValue("exceptionType", verificationException.getClass().getSimpleName())
                    .log("event=discarded_image_delete_deferred generationId={} exceptionType={}",
                            generationId, verificationException.getClass().getSimpleName());
        }
    }

    void deleteDiscardedImage(UUID generationId, String imageObjectKey, String reason) {
        try {
            imageStorage.delete(imageObjectKey);
            LOGGER.atInfo()
                    .addKeyValue("event", "discarded_image_deleted")
                    .addKeyValue("generationId", generationId.toString())
                    .addKeyValue("reason", reason)
                    .log("event=discarded_image_deleted generationId={} reason={}", generationId, reason);
        } catch (RuntimeException exception) {
            LOGGER.atWarn()
                    .addKeyValue("event", "discarded_image_delete_failed")
                    .addKeyValue("generationId", generationId.toString())
                    .addKeyValue("reason", reason)
                    .addKeyValue("exceptionType", exception.getClass().getSimpleName())
                    .log("event=discarded_image_delete_failed generationId={} reason={} exceptionType={}",
                            generationId, reason, exception.getClass().getSimpleName());
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
