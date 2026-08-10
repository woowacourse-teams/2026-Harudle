package com.harudle.diary.service;

import com.harudle.diary.domain.Diary;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.DiaryCreationClaim;
import com.harudle.generation.domain.ComicGeneration;
import com.harudle.generation.domain.GenerationUsage;
import com.harudle.generation.repository.ComicGenerationRepository;
import com.harudle.generation.repository.GenerationPromptRepository;
import com.harudle.generation.service.GenerationUsageService;
import com.harudle.generation.service.RequestFingerprintGenerator;
import com.harudle.generation.service.dto.GenerateComicCommand;
import com.harudle.generation.service.exception.GenerationUnavailableException;
import com.harudle.generation.service.exception.IdempotencyKeyConflictException;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiaryCreationTransactionService {

    private final DiaryRepository diaryRepository;
    private final GenerationPromptRepository generationPromptRepository;
    private final ComicGenerationRepository comicGenerationRepository;
    private final GenerationUsageService generationUsageService;
    private final RequestFingerprintGenerator requestFingerprintGenerator;
    private final Clock clock;
    private final Duration processingTimeout;

    public DiaryCreationTransactionService(
            DiaryRepository diaryRepository,
            GenerationPromptRepository generationPromptRepository,
            ComicGenerationRepository comicGenerationRepository,
            GenerationUsageService generationUsageService,
            RequestFingerprintGenerator requestFingerprintGenerator,
            Clock clock,
            @Value("${harudle.generation.processing-timeout}") Duration processingTimeout
    ) {
        validateProcessingTimeout(processingTimeout);
        this.diaryRepository = diaryRepository;
        this.generationPromptRepository = generationPromptRepository;
        this.comicGenerationRepository = comicGenerationRepository;
        this.generationUsageService = generationUsageService;
        this.requestFingerprintGenerator = requestFingerprintGenerator;
        this.clock = clock;
        this.processingTimeout = processingTimeout;
    }

    @Transactional
    public DiaryCreationClaim claim(CreateDiaryCommand command, boolean generationAvailable) {
        Optional<ComicGeneration> existingGeneration = comicGenerationRepository
                .findByIdempotencyKeyForUpdate(command.idempotencyKey());
        if (existingGeneration.isPresent()) {
            return createExistingClaim(command, existingGeneration.get());
        }
        if (!generationAvailable) {
            throw new GenerationUnavailableException("AI 생성 어댑터가 구성되지 않았습니다.");
        }
        return createNewClaim(command);
    }

    private DiaryCreationClaim createExistingClaim(
            CreateDiaryCommand command,
            ComicGeneration generation
    ) {
        Diary diary = diaryRepository.findById(generation.getDiaryId())
                .orElseThrow(() -> new IllegalStateException("멱등 요청의 일기를 찾을 수 없습니다."));
        GenerateComicCommand generationCommand = createGenerationCommand(command, diary);
        String requestFingerprint = requestFingerprintGenerator.generate(generationCommand);
        if (!generation.hasSameRequestFingerprint(requestFingerprint)) {
            throw new IdempotencyKeyConflictException();
        }
        interruptIfStale(generation);
        GenerationUsage usage = generationUsageService.getTodayUsage(command.userId());
        return toClaim(diary, generation, usage, false);
    }

    private DiaryCreationClaim createNewClaim(CreateDiaryCommand command) {
        Long promptId = generationPromptRepository.findFirstByOrderByIdDesc()
                .orElseThrow(() -> new GenerationUnavailableException("사용할 생성 프롬프트가 없습니다."))
                .getId();
        Diary diary = diaryRepository.save(Diary.create(
                command.userId(),
                command.diaryDate(),
                command.sourceText()
        ));
        GenerateComicCommand generationCommand = createGenerationCommand(command, diary);
        ComicGeneration generation = ComicGeneration.start(
                diary.getId(),
                promptId,
                command.idempotencyKey(),
                requestFingerprintGenerator.generate(generationCommand)
        );
        ComicGeneration savedGeneration = comicGenerationRepository.saveAndFlush(generation);
        GenerationUsage usage = generationUsageService.incrementTodayUsage(command.userId());
        return toClaim(diary, savedGeneration, usage, true);
    }

    private void interruptIfStale(ComicGeneration generation) {
        generation.interruptIfStale(clock.instant(), processingTimeout);
    }

    private GenerateComicCommand createGenerationCommand(CreateDiaryCommand command, Diary diary) {
        return new GenerateComicCommand(
                command.userId(),
                diary.getId(),
                command.diaryDate(),
                command.sourceText(),
                command.idempotencyKey()
        );
    }

    private DiaryCreationClaim toClaim(
            Diary diary,
            ComicGeneration generation,
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
}
