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
import com.harudle.generation.service.exception.GenerationUnavailableException;
import com.harudle.generation.service.port.ComicImageGenerationRequest;
import com.harudle.generation.service.port.ComicImageGenerator;
import com.harudle.generation.service.port.GeneratedImage;
import com.harudle.generation.service.port.ImageStorage;
import com.harudle.generation.service.port.ImageStorageException;
import com.harudle.generation.service.port.ReferenceImage;
import com.harudle.generation.service.port.StoryboardGenerationRequest;
import com.harudle.generation.service.port.StoryboardGenerator;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

public class ClaimedComicGenerationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClaimedComicGenerationService.class);
    private static final int MAX_IMAGE_OBJECT_KEY_BYTES = 1024;

    private final RequestFingerprintGenerator requestFingerprintGenerator;
    private final GenerationPromptRepository generationPromptRepository;
    private final ComicGenerationRepository comicGenerationRepository;
    private final ObjectProvider<StoryboardGenerator> storyboardGeneratorProvider;
    private final ObjectProvider<ComicImageGenerator> comicImageGeneratorProvider;
    private final ObjectProvider<ImageStorage> imageStorageProvider;
    private final ComicGenerationCompletionService completionService;

    public ClaimedComicGenerationService(
            RequestFingerprintGenerator requestFingerprintGenerator,
            GenerationPromptRepository generationPromptRepository,
            ComicGenerationRepository comicGenerationRepository,
            ObjectProvider<StoryboardGenerator> storyboardGeneratorProvider,
            ObjectProvider<ComicImageGenerator> comicImageGeneratorProvider,
            ObjectProvider<ImageStorage> imageStorageProvider,
            ComicGenerationCompletionService completionService
    ) {
        this.requestFingerprintGenerator = requestFingerprintGenerator;
        this.generationPromptRepository = generationPromptRepository;
        this.comicGenerationRepository = comicGenerationRepository;
        this.storyboardGeneratorProvider = storyboardGeneratorProvider;
        this.comicImageGeneratorProvider = comicImageGeneratorProvider;
        this.imageStorageProvider = imageStorageProvider;
        this.completionService = completionService;
    }

    public boolean isAvailable() {
        return findAdapters() != null;
    }

    public ComicGenerationResult generate(GenerateComicCommand command, UUID generationId) {
        GenerationAdapters adapters = requireAdapters();
        ComicGeneration generation = findClaimedGeneration(command, generationId);
        GenerationPrompt prompt = generationPromptRepository.findById(generation.getGenerationPromptId())
                .orElseThrow(() -> new GenerationUnavailableException(
                        "사용할 생성 프롬프트가 없습니다."
        ));
        GeneratedComic generatedComic = executeExternalGeneration(command, prompt, generationId, adapters);
        ComicGeneration completedGeneration = completeGeneration(
                generationId,
                generatedComic,
                adapters.imageStorage()
        );
        return createResult(completedGeneration);
    }

    private ComicGeneration completeGeneration(
            UUID generationId,
            GeneratedComic generatedComic,
            ImageStorage imageStorage
    ) {
        try {
            return completionService.succeed(
                    generationId,
                    generatedComic.storyboard(),
                    generatedComic.imageObjectKey()
            );
        } catch (RuntimeException exception) {
            deleteDiscardedImage(imageStorage, generatedComic.imageObjectKey());
            throw exception;
        }
    }

    private GenerationAdapters requireAdapters() {
        GenerationAdapters adapters = findAdapters();
        if (adapters == null) {
            throw new GenerationUnavailableException("AI 생성 어댑터가 구성되지 않았습니다.");
        }
        return adapters;
    }

    private GenerationAdapters findAdapters() {
        StoryboardGenerator storyboardGenerator = storyboardGeneratorProvider.getIfUnique();
        ComicImageGenerator comicImageGenerator = comicImageGeneratorProvider.getIfUnique();
        ImageStorage imageStorage = imageStorageProvider.getIfUnique();
        if (storyboardGenerator == null || comicImageGenerator == null || imageStorage == null) {
            return null;
        }
        return new GenerationAdapters(storyboardGenerator, comicImageGenerator, imageStorage);
    }

    private ComicGeneration findClaimedGeneration(GenerateComicCommand command, UUID generationId) {
        ComicGeneration generation = comicGenerationRepository.findById(generationId)
                .orElseThrow(() -> new IllegalStateException("만화 생성 기록을 찾을 수 없습니다."));
        validateClaim(generation, command);
        return generation;
    }

    private void validateClaim(ComicGeneration generation, GenerateComicCommand command) {
        String requestFingerprint = requestFingerprintGenerator.generate(command);
        boolean matchesClaim = generation.getDiaryId().equals(command.diaryId())
                && generation.getIdempotencyKey().equals(command.idempotencyKey())
                && generation.getRequestFingerprint().equals(requestFingerprint)
                && generation.getStatus() == GenerationStatus.PROCESSING;
        if (!matchesClaim) {
            throw new IllegalStateException(
                    "선점한 만화 생성 요청과 실행 명령이 일치하지 않습니다."
            );
        }
    }

    private GeneratedComic executeExternalGeneration(
            GenerateComicCommand command,
            GenerationPrompt prompt,
            UUID generationId,
            GenerationAdapters adapters
    ) {
        try {
            Storyboard storyboard = adapters.storyboardGenerator().generate(new StoryboardGenerationRequest(
                    command.diaryText(),
                    prompt.getStoryboardPromptText()
            ));
            ReferenceImage referenceImage = adapters.imageStorage().load(prompt.getImageAssetObjectKey());
            GeneratedImage generatedImage = adapters.comicImageGenerator().generate(new ComicImageGenerationRequest(
                    storyboard,
                    prompt.getImageStylePromptText(),
                    referenceImage
            ));
            String imageObjectKey = storeImage(adapters.imageStorage(), generatedImage);
            return new GeneratedComic(storyboard, imageObjectKey);
        } catch (AiGenerationException exception) {
            failGeneration(generationId, mapAiGenerationErrorCode(exception.getErrorType()));
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

    private String storeImage(ImageStorage imageStorage, GeneratedImage generatedImage) {
        String imageObjectKey = imageStorage.store(generatedImage);
        try {
            validateImageObjectKey(imageObjectKey);
            return imageObjectKey.strip();
        } catch (ImageStorageException exception) {
            if (imageObjectKey != null && !imageObjectKey.isBlank()) {
                deleteDiscardedImage(imageStorage, imageObjectKey);
            }
            throw exception;
        }
    }

    private void failGeneration(UUID generationId, GenerationErrorCode requestedErrorCode) {
        GenerationErrorCode effectiveErrorCode = completionService.fail(generationId, requestedErrorCode);
        if (effectiveErrorCode != requestedErrorCode) {
            throw new ComicGenerationFailedException(effectiveErrorCode);
        }
    }

    private void deleteDiscardedImage(ImageStorage imageStorage, String imageObjectKey) {
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

    private ComicGenerationResult createResult(ComicGeneration generation) {
        return new ComicGenerationResult(
                generation.getId(),
                generation.getStatus(),
                generation.getTitle(),
                generation.getImageObjectKey(),
                generation.getCompletedAt(),
                true
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

    private static void validateImageObjectKey(String imageObjectKey) {
        if (imageObjectKey == null || imageObjectKey.isBlank()) {
            throw new ImageStorageException("생성 이미지 Object Key는 필수입니다.");
        }
        int imageObjectKeyBytes = imageObjectKey.strip().getBytes(StandardCharsets.UTF_8).length;
        if (imageObjectKeyBytes > MAX_IMAGE_OBJECT_KEY_BYTES) {
            throw new ImageStorageException(
                    "생성 이미지 Object Key는 UTF-8 기준 1,024바이트 이하여야 합니다."
            );
        }
    }

    private record GeneratedComic(Storyboard storyboard, String imageObjectKey) {
    }

    private record GenerationAdapters(
            StoryboardGenerator storyboardGenerator,
            ComicImageGenerator comicImageGenerator,
            ImageStorage imageStorage
    ) {
    }
}
