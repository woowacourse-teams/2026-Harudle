package com.harudle.generation.diary.service;

import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.domain.GenerationErrorCode;
import com.harudle.generation.prompt.domain.GenerationPrompt;
import com.harudle.generation.diary.domain.ImageObjectKeyPolicy;
import com.harudle.generation.diary.domain.Storyboard;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.prompt.repository.GenerationPromptRepository;
import com.harudle.generation.diary.service.dto.CompletedDiaryGeneration;
import com.harudle.generation.diary.service.dto.GenerateDiaryImageCommand;
import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.exception.DiaryGenerationFailedException;
import com.harudle.generation.diary.service.exception.GenerationUnavailableException;
import com.harudle.generation.diary.service.port.dto.DiaryImageGenerationRequest;
import com.harudle.generation.diary.service.port.DiaryImageGenerator;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import com.harudle.generation.diary.service.port.dto.StoryboardGenerationRequest;
import com.harudle.generation.diary.service.port.StoryboardGenerator;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClaimedDiaryGenerationService implements DiaryGenerationExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClaimedDiaryGenerationService.class);
    private final RequestFingerprintGenerator requestFingerprintGenerator;
    private final GenerationPromptRepository generationPromptRepository;
    private final DiaryGenerationRepository diaryGenerationRepository;
    private final StoryboardGenerator storyboardGenerator;
    private final DiaryImageGenerator diaryImageGenerator;
    private final ImageStorage imageStorage;
    private final DiaryGenerationCompletionService completionService;

    public ClaimedDiaryGenerationService(
            RequestFingerprintGenerator requestFingerprintGenerator,
            GenerationPromptRepository generationPromptRepository,
            DiaryGenerationRepository diaryGenerationRepository,
            StoryboardGenerator storyboardGenerator,
            DiaryImageGenerator diaryImageGenerator,
            ImageStorage imageStorage,
            DiaryGenerationCompletionService completionService
    ) {
        this.requestFingerprintGenerator = requestFingerprintGenerator;
        this.generationPromptRepository = generationPromptRepository;
        this.diaryGenerationRepository = diaryGenerationRepository;
        this.storyboardGenerator = storyboardGenerator;
        this.diaryImageGenerator = diaryImageGenerator;
        this.imageStorage = imageStorage;
        this.completionService = completionService;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public CompletedDiaryGeneration generate(GenerateDiaryImageCommand command, UUID generationId) {
        DiaryGeneration generation = findClaimedGeneration(command, generationId);
        GenerationPrompt prompt = generationPromptRepository.findById(generation.getGenerationPromptId())
                .orElseThrow(GenerationUnavailableException::promptNotConfigured);
        GeneratedDiaryImage generatedDiaryImage = executeExternalGeneration(command, prompt, generationId);
        DiaryGeneration completedGeneration = completeGeneration(generationId, generatedDiaryImage);
        return createResult(completedGeneration);
    }

    private DiaryGeneration completeGeneration(
            UUID generationId,
            GeneratedDiaryImage generatedDiaryImage
    ) {
        try {
            DiaryGeneration completedGeneration = completionService.succeed(
                    generationId,
                    generatedDiaryImage.storyboard(),
                    generatedDiaryImage.imageObjectKey()
            );
            if (completedGeneration.notUsesImageObjectKey(generatedDiaryImage.imageObjectKey())) {
                deleteDiscardedImage(generatedDiaryImage.imageObjectKey());
            }
            return completedGeneration;
        } catch (RuntimeException exception) {
            deleteImageIfSafelyDiscardable(
                    generationId,
                    generatedDiaryImage.imageObjectKey(),
                    exception
            );
            throw exception;
        }
    }

    private void deleteImageIfSafelyDiscardable(
            UUID generationId,
            String imageObjectKey,
            RuntimeException completionException
    ) {
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

    private static boolean canDeleteImage(DiaryGeneration generation, String imageObjectKey) {
        return switch (generation.getStatus()) {
            case PROCESSING -> false;
            case FAILED -> true;
            case SUCCEEDED -> generation.notUsesImageObjectKey(imageObjectKey);
        };
    }

    private DiaryGeneration findClaimedGeneration(GenerateDiaryImageCommand command, UUID generationId) {
        DiaryGeneration generation = diaryGenerationRepository.findById(generationId)
                .orElseThrow(() -> new IllegalStateException("그림일기 생성 기록을 찾을 수 없습니다."));
        validateClaim(generation, command);
        return generation;
    }

    private void validateClaim(DiaryGeneration generation, GenerateDiaryImageCommand command) {
        String requestFingerprint = requestFingerprintGenerator.generate(command);
        boolean matchesClaim = generation.matchesExecutableClaim(
                command.diaryId(),
                command.idempotencyKey(),
                requestFingerprint
        );
        if (!matchesClaim) {
            throw new IllegalStateException(
                    "선점한 그림일기 생성 요청과 실행 명령이 일치하지 않습니다."
            );
        }
    }

    private GeneratedDiaryImage executeExternalGeneration(
            GenerateDiaryImageCommand command,
            GenerationPrompt prompt,
            UUID generationId
    ) {
        try {
            Storyboard storyboard = storyboardGenerator.generate(new StoryboardGenerationRequest(
                    command.diaryText(),
                    prompt.getStoryboardPromptText()
            ));
            ReferenceImage referenceImage = imageStorage.load(prompt.getImageAssetObjectKey());
            GeneratedImage generatedImage = diaryImageGenerator.generate(new DiaryImageGenerationRequest(
                    storyboard,
                    prompt.getImageStylePromptText(),
                    referenceImage
            ));
            String imageObjectKey = storeImage(generationId, generatedImage);
            return new GeneratedDiaryImage(storyboard, imageObjectKey);
        } catch (AiGenerationException exception) {
            failGeneration(generationId, mapAiGenerationErrorCode(exception.errorType()));
            throw exception;
        } catch (ImageStorageException exception) {
            failGeneration(generationId, GenerationErrorCode.IMAGE_STORAGE_ERROR);
            throw exception;
        } catch (RuntimeException exception) {
            failGeneration(generationId, GenerationErrorCode.AI_PROVIDER_ERROR);
            throw new AiGenerationException(
                    AiGenerationErrorType.PROVIDER_ERROR,
                    "AI 생성 결과를 처리하지 못했습니다.",
                    exception
            );
        }
    }

    private String storeImage(UUID generationId, GeneratedImage generatedImage) {
        String imageObjectKey = imageStorage.store(generationId, generatedImage);
        try {
            return ImageObjectKeyPolicy.normalizeRequired(imageObjectKey, "생성 이미지 Object Key");
        } catch (IllegalArgumentException exception) {
            if (imageObjectKey != null) {
                deleteDiscardedImage(imageObjectKey);
            }

            throw new ImageStorageException(exception.getMessage(), exception);
        }
    }

    private void failGeneration(UUID generationId, GenerationErrorCode requestedErrorCode) {
        GenerationErrorCode effectiveErrorCode = completionService.fail(generationId, requestedErrorCode);
        if (effectiveErrorCode != requestedErrorCode) {
            throw new DiaryGenerationFailedException(effectiveErrorCode);
        }
    }

    private void deleteDiscardedImage(String imageObjectKey) {
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

    private CompletedDiaryGeneration createResult(DiaryGeneration generation) {
        return new CompletedDiaryGeneration(
                generation.getId(),
                generation.getTitle(),
                generation.getImageObjectKey(),
                generation.getCompletedAt()
        );
    }

    private GenerationErrorCode mapAiGenerationErrorCode(AiGenerationErrorType errorType) {
        return switch (errorType) {
            case PROVIDER_ERROR -> GenerationErrorCode.AI_PROVIDER_ERROR;
            case TIMEOUT -> GenerationErrorCode.AI_PROVIDER_TIMEOUT;
        };
    }

    private record GeneratedDiaryImage(Storyboard storyboard, String imageObjectKey) {
    }
}
