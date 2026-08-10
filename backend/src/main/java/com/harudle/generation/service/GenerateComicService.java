package com.harudle.generation.service;

import java.time.Instant;
import java.util.Optional;

import com.harudle.generation.domain.ComicGeneration;
import com.harudle.generation.domain.GenerationPrompt;
import com.harudle.generation.domain.Storyboard;
import com.harudle.generation.repository.ComicGenerationRepository;
import com.harudle.generation.repository.GenerationPromptRepository;

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
        if (existingGeneration.isPresent()) {
            return handleExistingGeneration(existingGeneration.get(), requestFingerprint);
        }

        GenerationPrompt prompt = findLatestPrompt();
        ComicGeneration generation = startGeneration(command, prompt, requestFingerprint);

        Storyboard storyboard = generateStoryboard(command, prompt);
        ReferenceImage referenceImage = imageStorage.load(prompt.getImageAssetObjectKey());
        GeneratedImage generatedImage = generateImage(storyboard, prompt, referenceImage);
        String imageObjectKey = imageStorage.store(generatedImage);

        generation.succeed(storyboard, imageObjectKey, Instant.now());
        ComicGeneration completedGeneration = comicGenerationRepository.saveAndFlush(generation);
        return createResult(completedGeneration, true);
    }

    private ComicGenerationResult handleExistingGeneration(
            ComicGeneration generation,
            String requestFingerprint
    ) {
        if (!generation.getRequestFingerprint().equals(requestFingerprint)) {
            throw new IdempotencyKeyConflictException();
        }

        return switch (generation.getStatus()) {
            case SUCCEEDED -> createResult(generation, false);
            case PROCESSING -> throw new GenerationInProgressException();
            case FAILED -> throw new ComicGenerationFailedException(generation.getErrorCode());
        };
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
}
