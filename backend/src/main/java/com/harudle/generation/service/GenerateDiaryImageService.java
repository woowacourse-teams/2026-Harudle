package com.harudle.generation.service;

import com.harudle.generation.configuration.GenerationLifecycleProperties;
import com.harudle.generation.domain.DiaryGeneration;
import com.harudle.generation.domain.GenerationErrorCode;
import com.harudle.generation.domain.GenerationPrompt;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.domain.Storyboard;
import com.harudle.generation.repository.DiaryGenerationRepository;
import com.harudle.generation.repository.GenerationPromptRepository;
import com.harudle.generation.service.dto.DiaryGenerationResult;
import com.harudle.generation.service.dto.GenerateDiaryImageCommand;
import com.harudle.generation.service.exception.AiGenerationErrorType;
import com.harudle.generation.service.exception.AiGenerationException;
import com.harudle.generation.service.exception.DiaryGenerationFailedException;
import com.harudle.generation.service.exception.GenerationInProgressException;
import com.harudle.generation.service.exception.IdempotencyKeyConflictException;
import com.harudle.generation.service.port.DiaryImageGenerationRequest;
import com.harudle.generation.service.port.DiaryImageGenerator;
import com.harudle.generation.service.port.GeneratedImage;
import com.harudle.generation.service.port.ImageStorage;
import com.harudle.generation.service.port.ImageStorageException;
import com.harudle.generation.service.port.ReferenceImage;
import com.harudle.generation.service.port.StoryboardGenerationRequest;
import com.harudle.generation.service.port.StoryboardGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

public class GenerateDiaryImageService {

    private static final Logger log = LoggerFactory.getLogger(GenerateDiaryImageService.class);

    private final RequestFingerprintGenerator requestFingerprintGenerator;
    private final GenerationLifecycleProperties generationLifecycleProperties;
    private final Clock clock;
    private final GenerationPromptRepository generationPromptRepository;
    private final DiaryGenerationRepository diaryGenerationRepository;
    private final StoryboardGenerator storyboardGenerator;
    private final DiaryImageGenerator diaryImageGenerator;
    private final ImageStorage imageStorage;

    public GenerateDiaryImageService(
            RequestFingerprintGenerator requestFingerprintGenerator,
            GenerationLifecycleProperties generationLifecycleProperties,
            Clock clock,
            GenerationPromptRepository generationPromptRepository,
            DiaryGenerationRepository diaryGenerationRepository,
            StoryboardGenerator storyboardGenerator,
            DiaryImageGenerator diaryImageGenerator,
            ImageStorage imageStorage
    ) {
        this.requestFingerprintGenerator = requestFingerprintGenerator;
        this.generationLifecycleProperties = generationLifecycleProperties;
        this.clock = clock;
        this.generationPromptRepository = generationPromptRepository;
        this.diaryGenerationRepository = diaryGenerationRepository;
        this.storyboardGenerator = storyboardGenerator;
        this.diaryImageGenerator = diaryImageGenerator;
        this.imageStorage = imageStorage;
    }

    public DiaryGenerationResult generate(GenerateDiaryImageCommand command) {
        String requestFingerprint = requestFingerprintGenerator.generate(command);
        Optional<DiaryGeneration> existingGeneration = diaryGenerationRepository
                .findByIdempotencyKey(command.idempotencyKey());
        return existingGeneration.map(diaryGeneration -> handleExistingGeneration(diaryGeneration, requestFingerprint))
                .orElseGet(() -> generateNewDiaryImage(command, requestFingerprint));
    }

    private DiaryGenerationResult generateNewDiaryImage(
            GenerateDiaryImageCommand command,
            String requestFingerprint
    ) {
        GenerationPrompt prompt = findLatestPrompt();
        DiaryGeneration generation;
        try {
            generation = startGeneration(command, prompt, requestFingerprint);
        } catch (DataIntegrityViolationException exception) {
            return handleConcurrentGeneration(command, requestFingerprint, exception);
        }

        return executeGeneration(command, prompt, generation);
    }

    private DiaryGenerationResult executeGeneration(
            GenerateDiaryImageCommand command,
            GenerationPrompt prompt,
            DiaryGeneration generation
    ) {
        Storyboard storyboard;
        String imageObjectKey;
        try {
            storyboard = generateStoryboard(command, prompt);
            ReferenceImage referenceImage = imageStorage.load(prompt.getImageAssetObjectKey());
            GeneratedImage generatedImage = generateImage(storyboard, prompt, referenceImage);
            imageObjectKey = imageStorage.store(generation.getId(), generatedImage);
        } catch (AiGenerationException exception) {
            failGeneration(generation, mapAiGenerationErrorCode(exception.getErrorType()), exception);
            throw exception;
        } catch (ImageStorageException exception) {
            failGeneration(generation, GenerationErrorCode.IMAGE_STORAGE_ERROR, exception);
            throw exception;
        } catch (RuntimeException exception) {
            failGeneration(generation, GenerationErrorCode.GENERATION_INTERRUPTED, exception);
            throw exception;
        }

        return succeedGeneration(generation, storyboard, imageObjectKey);
    }

    private DiaryGenerationResult handleConcurrentGeneration(
            GenerateDiaryImageCommand command,
            String requestFingerprint,
            DataIntegrityViolationException exception
    ) {
        DiaryGeneration generation = diaryGenerationRepository
                .findByIdempotencyKey(command.idempotencyKey())
                .orElseThrow(() -> exception);
        return handleExistingGeneration(generation, requestFingerprint);
    }

    private DiaryGenerationResult handleExistingGeneration(
            DiaryGeneration generation,
            String requestFingerprint
    ) {
        if (!generation.getRequestFingerprint().equals(requestFingerprint)) {
            throw new IdempotencyKeyConflictException();
        }

        GenerationStatus status = generation.getStatus();
        if (status == GenerationStatus.PROCESSING) {
            return handleProcessingGeneration(generation);
        }
        return handleExistingGenerationStatus(generation);
    }

    private DiaryGenerationResult handleProcessingGeneration(DiaryGeneration generation) {
        Instant completedAt = clock.instant();
        Instant expiredBefore = completedAt.minus(generationLifecycleProperties.processingTimeout());
        Instant updatedAt = generation.getUpdatedAt();
        if (updatedAt == null || !updatedAt.isBefore(expiredBefore)) {
            throw new GenerationInProgressException();
        }

        int expiredCount = diaryGenerationRepository.expireProcessingGeneration(
                generation.getId(),
                expiredBefore,
                completedAt
        );
        if (expiredCount == 1) {
            throw new DiaryGenerationFailedException(GenerationErrorCode.GENERATION_INTERRUPTED);
        }

        DiaryGeneration currentGeneration = diaryGenerationRepository.findById(generation.getId())
                .orElseThrow(() -> new IllegalStateException("그림일기 생성 작업을 찾을 수 없습니다."));
        return handleExistingGenerationStatus(currentGeneration);
    }

    private DiaryGenerationResult handleExistingGenerationStatus(DiaryGeneration generation) {
        GenerationStatus status = generation.getStatus();
        if (status == GenerationStatus.SUCCEEDED) {
            return createResult(generation, false);
        }
        if (status == GenerationStatus.PROCESSING) {
            throw new GenerationInProgressException();
        }
        if (status == GenerationStatus.FAILED) {
            throw new DiaryGenerationFailedException(generation.getErrorCode());
        }
        throw new IllegalStateException("지원하지 않는 그림일기 생성 상태입니다.");
    }

    private GenerationPrompt findLatestPrompt() {
        return generationPromptRepository.findFirstByOrderByIdDesc()
                .orElseThrow(() -> new IllegalStateException("사용할 생성 프롬프트가 없습니다."));
    }

    private DiaryGeneration startGeneration(
            GenerateDiaryImageCommand command,
            GenerationPrompt prompt,
            String requestFingerprint
    ) {
        DiaryGeneration generation = DiaryGeneration.start(
                command.diaryId(),
                prompt.getId(),
                command.idempotencyKey(),
                requestFingerprint
        );
        return diaryGenerationRepository.saveAndFlush(generation);
    }

    private Storyboard generateStoryboard(GenerateDiaryImageCommand command, GenerationPrompt prompt) {
        return storyboardGenerator.generate(new StoryboardGenerationRequest(
                command.diaryText(),
                prompt.getStoryboardPromptText()
        ));
    }

    private GeneratedImage generateImage(
            Storyboard storyboard,
            GenerationPrompt prompt,
            ReferenceImage referenceImage
    ) {
        return diaryImageGenerator.generate(new DiaryImageGenerationRequest(
                storyboard,
                prompt.getImageStylePromptText(),
                referenceImage
        ));
    }

    private DiaryGenerationResult succeedGeneration(
            DiaryGeneration generation,
            Storyboard storyboard,
            String imageObjectKey
    ) {
        Instant completedAt = clock.instant();
        generation.succeed(storyboard, imageObjectKey, completedAt);
        int succeededCount = diaryGenerationRepository.succeedProcessingGeneration(
                generation.getId(),
                storyboard,
                storyboard.title(),
                imageObjectKey,
                completedAt,
                GenerationStatus.PROCESSING,
                GenerationStatus.SUCCEEDED
        );
        if (succeededCount == 1) {
            return createResult(generation, true);
        }

        DiaryGeneration currentGeneration = diaryGenerationRepository.findById(generation.getId())
                .orElseThrow(() -> new IllegalStateException("그림일기 생성 작업을 찾을 수 없습니다."));
        return handleExistingGenerationStatus(currentGeneration);
    }

    private DiaryGenerationResult createResult(DiaryGeneration generation, boolean newlyCreated) {
        return new DiaryGenerationResult(
                generation.getId(),
                generation.getStatus(),
                generation.getTitle(),
                generation.getImageObjectKey(),
                generation.getCompletedAt(),
                newlyCreated
        );
    }

    private GenerationErrorCode mapAiGenerationErrorCode(AiGenerationErrorType errorType) {
        if (errorType == AiGenerationErrorType.PROVIDER_ERROR) {
            return GenerationErrorCode.AI_PROVIDER_ERROR;
        }
        if (errorType == AiGenerationErrorType.TIMEOUT) {
            return GenerationErrorCode.AI_PROVIDER_TIMEOUT;
        }
        return GenerationErrorCode.GENERATION_INTERRUPTED;
    }

    private void failGeneration(
            DiaryGeneration generation,
            GenerationErrorCode errorCode,
            RuntimeException originalException
    ) {
        try {
            Instant completedAt = clock.instant();
            generation.fail(errorCode, completedAt);
            diaryGenerationRepository.failProcessingGeneration(
                    generation.getId(),
                    errorCode,
                    completedAt,
                    GenerationStatus.PROCESSING,
                    GenerationStatus.FAILED
            );
        } catch (RuntimeException failureTransitionException) {
            if (failureTransitionException != originalException) {
                originalException.addSuppressed(failureTransitionException);
            }
            log.error(
                    "그림일기 생성 실패 상태 저장에 실패했습니다. generationId={}",
                    generation.getId(),
                    failureTransitionException
            );
        }
    }
}
