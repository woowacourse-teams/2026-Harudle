package com.harudle.diary.service;

import com.harudle.diary.domain.Diary;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.exception.DiaryNotFoundException;
import com.harudle.generation.configuration.GenerationLifecycleProperties;
import com.harudle.generation.domain.DiaryGeneration;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.domain.GenerationUsage;
import com.harudle.generation.repository.DiaryGenerationRepository;
import com.harudle.generation.repository.GenerationPromptRepository;
import com.harudle.generation.service.GenerationUsageService;
import com.harudle.generation.service.RequestFingerprintGenerator;
import com.harudle.generation.service.dto.GenerateDiaryImageCommand;
import com.harudle.generation.service.exception.GenerationUnavailableException;
import com.harudle.generation.service.exception.IdempotencyKeyConflictException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DiaryCreationTransactionService {

    private final DiaryRepository diaryRepository;
    private final GenerationPromptRepository generationPromptRepository;
    private final DiaryGenerationRepository diaryGenerationRepository;
    private final GenerationUsageService generationUsageService;
    private final RequestFingerprintGenerator requestFingerprintGenerator;
    private final Clock clock;
    private final Duration processingTimeout;

    DiaryCreationTransactionService(
            DiaryRepository diaryRepository,
            GenerationPromptRepository generationPromptRepository,
            DiaryGenerationRepository diaryGenerationRepository,
            GenerationUsageService generationUsageService,
            RequestFingerprintGenerator requestFingerprintGenerator,
            @Qualifier("serviceClock")
            Clock clock,
            GenerationLifecycleProperties generationLifecycleProperties
    ) {
        Duration processingTimeout = generationLifecycleProperties.processingTimeout();
        validateProcessingTimeout(processingTimeout);
        this.diaryRepository = diaryRepository;
        this.generationPromptRepository = generationPromptRepository;
        this.diaryGenerationRepository = diaryGenerationRepository;
        this.generationUsageService = generationUsageService;
        this.requestFingerprintGenerator = requestFingerprintGenerator;
        this.clock = clock;
        this.processingTimeout = processingTimeout;
    }

    @Transactional
    DiaryCreationClaim claim(CreateDiaryCommand command, boolean generationAvailable) {
        return diaryGenerationRepository
                .findByIdempotencyKeyForUpdate(command.idempotencyKey())
                .map(generation -> createExistingClaim(command, generation))
                .orElseGet(() -> createNewClaim(command, generationAvailable));
    }

    @Transactional
    Optional<DiaryCreationClaim> findExistingClaim(CreateDiaryCommand command) {
        return diaryGenerationRepository
                .findByIdempotencyKeyForUpdate(command.idempotencyKey())
                .map(generation -> createExistingClaim(command, generation));
    }

    private DiaryCreationClaim createExistingClaim(
            CreateDiaryCommand command,
            DiaryGeneration generation
    ) {
        Diary diary = diaryRepository.findByIdIncludingDeletedForUpdate(generation.getDiaryId())
                .orElseThrow(DiaryNotFoundException::new);
        validateDiaryAccess(diary, generation);
        GenerateDiaryImageCommand generationCommand = createGenerationCommand(command, diary);
        String requestFingerprint = requestFingerprintGenerator.generate(generationCommand);
        if (!generation.hasSameRequestFingerprint(requestFingerprint)) {
            throw new IdempotencyKeyConflictException();
        }
        interruptIfStale(generation, diary);
        GenerationUsage usage = generationUsageService.getTodayUsage(command.userId());
        return toClaim(diary, generation, usage, false);
    }

    private DiaryCreationClaim createNewClaim(
            CreateDiaryCommand command,
            boolean generationAvailable
    ) {
        if (!generationAvailable) {
            throw GenerationUnavailableException.adaptersNotConfigured();
        }
        Long promptId = generationPromptRepository.findFirstByOrderByIdDesc()
                .orElseThrow(GenerationUnavailableException::promptNotConfigured)
                .getId();
        Diary diary = diaryRepository.save(Diary.create(
                command.userId(),
                command.diaryDate(),
                command.sourceText()
        ));
        GenerateDiaryImageCommand generationCommand = createGenerationCommand(command, diary);
        DiaryGeneration generation = DiaryGeneration.start(
                diary.getId(),
                promptId,
                command.idempotencyKey(),
                requestFingerprintGenerator.generate(generationCommand)
        );
        DiaryGeneration savedGeneration = diaryGenerationRepository.saveAndFlush(generation);
        GenerationUsage usage = generationUsageService.incrementTodayUsage(command.userId());
        return toClaim(diary, savedGeneration, usage, true);
    }

    private void interruptIfStale(DiaryGeneration generation, Diary diary) {
        Instant currentTime = clock.instant();
        generation.interruptIfStale(currentTime, processingTimeout);
        if (generation.getStatus() == GenerationStatus.FAILED) {
            diary.delete(generation.getCompletedAt());
        }
    }

    private GenerateDiaryImageCommand createGenerationCommand(CreateDiaryCommand command, Diary diary) {
        return new GenerateDiaryImageCommand(
                command.userId(),
                diary.getId(),
                command.diaryDate(),
                command.sourceText(),
                command.idempotencyKey()
        );
    }

    private DiaryCreationClaim toClaim(
            Diary diary,
            DiaryGeneration generation,
            GenerationUsage usage,
            boolean newlyCreated
    ) {
        return new DiaryCreationClaim(
                diary.getId(),
                diary.getDiaryDate(),
                diary.getSourceText(),
                diary.getCreatedAt(),
                generation.getId(),
                generation.getStatus(),
                generation.getTitle(),
                generation.getImageObjectKey(),
                generation.getCompletedAt(),
                generation.getErrorCode(),
                usage,
                newlyCreated
        );
    }

    private static void validateProcessingTimeout(Duration processingTimeout) {
        if (processingTimeout == null || processingTimeout.isZero() || processingTimeout.isNegative()) {
            throw new IllegalArgumentException("생성 처리 제한 시간은 양수여야 합니다.");
        }
    }

    private static void validateDiaryAccess(Diary diary, DiaryGeneration generation) {
        if (diary.isDeleted() && generation.getStatus() != GenerationStatus.FAILED) {
            throw new DiaryNotFoundException();
        }
    }
}
