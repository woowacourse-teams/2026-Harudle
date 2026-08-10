package com.harudle.generation.service;

import com.harudle.generation.domain.ComicGeneration;
import com.harudle.generation.domain.GenerationErrorCode;
import com.harudle.generation.domain.GenerationPrompt;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.domain.Storyboard;
import com.harudle.generation.repository.ComicGenerationRepository;
import com.harudle.generation.repository.GenerationPromptRepository;
import com.harudle.generation.service.dto.ComicGenerationResult;
import com.harudle.generation.service.dto.GenerateComicCommand;
import com.harudle.generation.service.exception.AiGenerationErrorType;
import com.harudle.generation.service.exception.AiGenerationException;
import com.harudle.generation.service.exception.ComicGenerationFailedException;
import com.harudle.generation.service.exception.GenerationInProgressException;
import com.harudle.generation.service.exception.IdempotencyKeyConflictException;
import com.harudle.generation.service.port.ComicImageGenerationRequest;
import com.harudle.generation.service.port.ComicImageGenerator;
import com.harudle.generation.service.port.GeneratedImage;
import com.harudle.generation.service.port.ImageStorage;
import com.harudle.generation.service.port.ImageStorageException;
import com.harudle.generation.service.port.ReferenceImage;
import com.harudle.generation.service.port.StoryboardGenerationRequest;
import com.harudle.generation.service.port.StoryboardGenerator;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;

public class GenerateComicService {

    private final RequestFingerprintGenerator requestFingerprintGenerator;
    private final GenerationPromptRepository generationPromptRepository;
    private final ComicGenerationRepository comicGenerationRepository;
    private final StoryboardGenerator storyboardGenerator;
    private final ComicImageGenerator comicImageGenerator;
    private final ImageStorage imageStorage;

    public GenerateComicService(
            RequestFingerprintGenerator requestFingerprintGenerator,
            GenerationPromptRepository generationPromptRepository,
            ComicGenerationRepository comicGenerationRepository,
            StoryboardGenerator storyboardGenerator,
            ComicImageGenerator comicImageGenerator,
            ImageStorage imageStorage
    ) {
        this.requestFingerprintGenerator = requestFingerprintGenerator;
        this.generationPromptRepository = generationPromptRepository;
        this.comicGenerationRepository = comicGenerationRepository;
        this.storyboardGenerator = storyboardGenerator;
        this.comicImageGenerator = comicImageGenerator;
        this.imageStorage = imageStorage;
    }

    public ComicGenerationResult generate(GenerateComicCommand command) {
        String requestFingerprint = requestFingerprintGenerator.generate(command);
        Optional<ComicGeneration> existingGeneration = comicGenerationRepository
                .findByIdempotencyKey(command.idempotencyKey());
        return existingGeneration.map(comicGeneration -> handleExistingGeneration(comicGeneration, requestFingerprint))
                .orElseGet(() -> generateNewComic(command, requestFingerprint));

    }

    private ComicGenerationResult generateNewComic(
            GenerateComicCommand command,
            String requestFingerprint
    ) {
        GenerationPrompt prompt = findLatestPrompt();
        ComicGeneration generation;
        try {
            generation = startGeneration(command, prompt, requestFingerprint);
        } catch (DataIntegrityViolationException exception) {
            return handleConcurrentGeneration(command, requestFingerprint, exception);
        }

        return executeGeneration(command, prompt, generation);
    }

    private ComicGenerationResult executeGeneration(
            GenerateComicCommand command,
            GenerationPrompt prompt,
            ComicGeneration generation
    ) {
        try {
            Storyboard storyboard = generateStoryboard(command, prompt);
            ReferenceImage referenceImage = imageStorage.load(prompt.getImageAssetObjectKey());
            GeneratedImage generatedImage = generateImage(storyboard, prompt, referenceImage);
            String imageObjectKey = imageStorage.store(generatedImage);
            return succeedGeneration(generation, storyboard, imageObjectKey);
        } catch (AiGenerationException exception) {
            failGeneration(generation, mapAiGenerationErrorCode(exception.getErrorType()));
            throw exception;
        } catch (ImageStorageException exception) {
            failGeneration(generation, GenerationErrorCode.IMAGE_STORAGE_ERROR);
            throw exception;
        }
    }

    private ComicGenerationResult handleConcurrentGeneration(
            GenerateComicCommand command,
            String requestFingerprint,
            DataIntegrityViolationException exception
    ) {
        ComicGeneration generation = comicGenerationRepository
                .findByIdempotencyKey(command.idempotencyKey())
                .orElseThrow(() -> exception);
        return handleExistingGeneration(generation, requestFingerprint);
    }

    private ComicGenerationResult handleExistingGeneration(
            ComicGeneration generation,
            String requestFingerprint
    ) {
        if (!generation.getRequestFingerprint().equals(requestFingerprint)) {
            throw new IdempotencyKeyConflictException();
        }

        GenerationStatus status = generation.getStatus();
        if (status == GenerationStatus.SUCCEEDED) {
            return createResult(generation, false);
        }
        if (status == GenerationStatus.PROCESSING) {
            throw new GenerationInProgressException();
        }
        if (status == GenerationStatus.FAILED) {
            throw new ComicGenerationFailedException(generation.getErrorCode());
        }
        throw new IllegalStateException("지원하지 않는 만화 생성 상태입니다.");
    }

    private GenerationPrompt findLatestPrompt() {
        return generationPromptRepository.findFirstByOrderByIdDesc()
                .orElseThrow(() -> new IllegalStateException("사용할 생성 프롬프트가 없습니다."));
    }

    private ComicGeneration startGeneration(
            GenerateComicCommand command,
            GenerationPrompt prompt,
            String requestFingerprint
    ) {
        ComicGeneration generation = ComicGeneration.start(
                command.diaryId(),
                prompt.getId(),
                command.idempotencyKey(),
                requestFingerprint
        );
        return comicGenerationRepository.saveAndFlush(generation);
    }

    private Storyboard generateStoryboard(GenerateComicCommand command, GenerationPrompt prompt) {
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
        return comicImageGenerator.generate(new ComicImageGenerationRequest(
                storyboard,
                prompt.getImageStylePromptText(),
                referenceImage
        ));
    }

    private ComicGenerationResult succeedGeneration(
            ComicGeneration generation,
            Storyboard storyboard,
            String imageObjectKey
    ) {
        generation.succeed(storyboard, imageObjectKey, Instant.now());
        ComicGeneration completedGeneration = comicGenerationRepository.saveAndFlush(generation);
        return createResult(completedGeneration, true);
    }

    private ComicGenerationResult createResult(ComicGeneration generation, boolean newlyCreated) {
        return new ComicGenerationResult(
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
        throw new IllegalArgumentException("지원하지 않는 AI 생성 오류 타입입니다.");
    }

    private void failGeneration(ComicGeneration generation, GenerationErrorCode errorCode) {
        generation.fail(errorCode, Instant.now());
        comicGenerationRepository.saveAndFlush(generation);
    }
}
