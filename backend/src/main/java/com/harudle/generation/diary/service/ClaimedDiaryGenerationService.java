package com.harudle.generation.diary.service;

import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.domain.GenerationErrorCode;
import com.harudle.generation.diary.domain.GenerationTokenUsage;
import com.harudle.generation.diary.domain.ImageObjectKeyPolicy;
import com.harudle.generation.diary.domain.Storyboard;
import com.harudle.generation.diary.domain.StoryboardProviderInfo;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.dto.CompletedDiaryGeneration;
import com.harudle.generation.diary.service.dto.GenerateDiaryImageCommand;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.exception.DiaryGenerationFailedException;
import com.harudle.generation.diary.service.exception.GenerationUnavailableException;
import com.harudle.generation.diary.service.port.DiaryImageGenerator;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.dto.DiaryImageGenerationRequest;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import com.harudle.generation.diary.service.port.dto.GeneratedStoryboard;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import com.harudle.generation.diary.service.port.dto.StoryboardGenerationRequest;
import com.harudle.generation.diary.service.storyboard.StoryboardGenerationService;
import com.harudle.generation.prompt.domain.GenerationPrompt;
import com.harudle.generation.prompt.repository.GenerationPromptRepository;
import java.util.UUID;

public final class ClaimedDiaryGenerationService implements DiaryGenerationExecutor {

    private final RequestFingerprintGenerator requestFingerprintGenerator;
    private final GenerationPromptRepository generationPromptRepository;
    private final DiaryGenerationRepository diaryGenerationRepository;
    private final StoryboardGenerationService storyboardGenerationService;
    private final DiaryImageGenerator diaryImageGenerator;
    private final ImageStorage imageStorage;
    private final DiaryGenerationCompletionService completionService;
    private final DiscardedGenerationImageCleaner imageCleaner;
    private final GenerationLifecycleMetrics lifecycleMetrics;

    public ClaimedDiaryGenerationService(
            RequestFingerprintGenerator requestFingerprintGenerator,
            GenerationPromptRepository generationPromptRepository,
            DiaryGenerationRepository diaryGenerationRepository,
            StoryboardGenerationService storyboardGenerationService,
            DiaryImageGenerator diaryImageGenerator,
            ImageStorage imageStorage,
            DiaryGenerationCompletionService completionService,
            GenerationLifecycleMetrics lifecycleMetrics
    ) {
        this.requestFingerprintGenerator = requestFingerprintGenerator;
        this.generationPromptRepository = generationPromptRepository;
        this.diaryGenerationRepository = diaryGenerationRepository;
        this.storyboardGenerationService = storyboardGenerationService;
        this.diaryImageGenerator = diaryImageGenerator;
        this.imageStorage = imageStorage;
        this.completionService = completionService;
        this.imageCleaner = new DiscardedGenerationImageCleaner(diaryGenerationRepository, imageStorage);
        this.lifecycleMetrics = lifecycleMetrics;
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
        DiaryGeneration completedGeneration;
        try {
            completedGeneration = completeGeneration(generationId, generatedDiaryImage);
        } catch (DiaryGenerationFailedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            lifecycleMetrics.unexpectedFailure(generationId, GenerationLifecycleMetrics.Phase.COMPLETION, exception);
            throw exception;
        }
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
                    generatedDiaryImage.imageObjectKey(),
                    generatedDiaryImage.tokenUsage(),
                    generatedDiaryImage.providerInfo()
            );
            imageCleaner.deleteIfUnused(completedGeneration, generatedDiaryImage.imageObjectKey());
            return completedGeneration;
        } catch (RuntimeException exception) {
            imageCleaner.deleteIfSafelyDiscardable(
                    generationId,
                    generatedDiaryImage.imageObjectKey(),
                    exception
            );
            throw exception;
        }
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
        GenerationLifecycleMetrics.Phase phase = GenerationLifecycleMetrics.Phase.STORYBOARD;
        StoryboardProviderInfo providerInfo = null;
        try {
            GeneratedStoryboard generatedStoryboard = storyboardGenerationService.generate(
                    new StoryboardGenerationRequest(command.diaryText(), prompt.getStoryboardPromptText()));
            providerInfo = generatedStoryboard.providerInfo();
            Storyboard storyboard = generatedStoryboard.storyboard();
            phase = GenerationLifecycleMetrics.Phase.REFERENCE_LOAD;
            ReferenceImage referenceImage = imageStorage.load(prompt.getImageAssetObjectKey());
            phase = GenerationLifecycleMetrics.Phase.IMAGE_GENERATION;
            GeneratedImage generatedImage = diaryImageGenerator.generate(new DiaryImageGenerationRequest(
                    storyboard,
                    prompt.getImageStylePromptText(),
                    referenceImage
            ));
            phase = GenerationLifecycleMetrics.Phase.IMAGE_STORE;
            String imageObjectKey = storeImage(generationId, generatedImage);
            return new GeneratedDiaryImage(storyboard, imageObjectKey, generatedStoryboard.tokenUsage(), providerInfo);
        } catch (AiGenerationException exception) {
            failGeneration(generationId, exception.errorCode(), providerInfo);
            throw exception;
        } catch (ImageStorageException exception) {
            failGeneration(generationId, GenerationErrorCode.IMAGE_STORAGE_ERROR, providerInfo);
            throw exception;
        } catch (RuntimeException exception) {
            lifecycleMetrics.unexpectedFailure(generationId, phase, exception);
            if (phase == GenerationLifecycleMetrics.Phase.REFERENCE_LOAD
                    || phase == GenerationLifecycleMetrics.Phase.IMAGE_STORE) {
                failGeneration(generationId, GenerationErrorCode.IMAGE_STORAGE_ERROR, providerInfo);
                throw new ImageStorageException("이미지 저장소 요청을 처리하지 못했습니다.", exception);
            }
            failGeneration(generationId, GenerationErrorCode.GENERATION_INTERNAL_ERROR, providerInfo);
            throw exception;
        }
    }

    private String storeImage(UUID generationId, GeneratedImage generatedImage) {
        String imageObjectKey = imageStorage.store(generationId, generatedImage);
        try {
            return ImageObjectKeyPolicy.normalizeRequired(imageObjectKey, "생성 이미지 Object Key");
        } catch (IllegalArgumentException exception) {
            if (imageObjectKey != null) {
                imageCleaner.deleteDiscardedImage(generationId, imageObjectKey, "invalid_object_key");
            }

            throw new ImageStorageException(exception.getMessage(), exception);
        }
    }

    private void failGeneration(UUID generationId, GenerationErrorCode requestedErrorCode,
            StoryboardProviderInfo providerInfo) {
        GenerationErrorCode effectiveErrorCode = providerInfo == null
                ? completionService.fail(generationId, requestedErrorCode)
                : completionService.fail(generationId, requestedErrorCode, providerInfo);
        if (effectiveErrorCode != requestedErrorCode) {
            throw new DiaryGenerationFailedException(effectiveErrorCode);
        }
    }

    private CompletedDiaryGeneration createResult(DiaryGeneration generation) {
        return new CompletedDiaryGeneration(
                generation.getId(),
                generation.getTitle(),
                generation.getImageObjectKey(),
                generation.getCompletedAt(),
                generation.getTokenUsage()
        );
    }

    private record GeneratedDiaryImage(
            Storyboard storyboard,
            String imageObjectKey,
            GenerationTokenUsage tokenUsage,
            StoryboardProviderInfo providerInfo
    ) {
    }
}
