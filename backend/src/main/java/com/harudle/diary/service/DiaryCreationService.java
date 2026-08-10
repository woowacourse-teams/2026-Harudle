package com.harudle.diary.service;

import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.CreateDiaryResult;
import com.harudle.diary.service.dto.DiaryCreationClaim;
import com.harudle.diary.service.dto.DiaryGenerationResult;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.service.ClaimedComicGenerationService;
import com.harudle.generation.service.dto.ComicGenerationResult;
import com.harudle.generation.service.dto.GenerateComicCommand;
import com.harudle.generation.service.exception.ComicGenerationFailedException;
import com.harudle.generation.service.exception.GenerationInProgressException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class DiaryCreationService {

    private final DiaryCreationTransactionService transactionService;
    private final ClaimedComicGenerationService generationService;

    public DiaryCreationService(
            DiaryCreationTransactionService transactionService,
            ClaimedComicGenerationService generationService
    ) {
        this.transactionService = transactionService;
        this.generationService = generationService;
    }

    public CreateDiaryResult create(CreateDiaryCommand command) {
        DiaryCreationClaim claim = claim(command, generationService.isAvailable());
        if (!claim.newlyCreated()) {
            return handleExistingClaim(claim);
        }
        ComicGenerationResult generationResult = generationService.generate(
                createGenerationCommand(command, claim),
                claim.generationId()
        );
        return createResult(claim, generationResult, true);
    }

    private DiaryCreationClaim claim(CreateDiaryCommand command, boolean generationAvailable) {
        try {
            return transactionService.claim(command, generationAvailable);
        } catch (DataIntegrityViolationException exception) {
            return findConcurrentClaim(command, generationAvailable, exception);
        }
    }

    private DiaryCreationClaim findConcurrentClaim(
            CreateDiaryCommand command,
            boolean generationAvailable,
            DataIntegrityViolationException exception
    ) {
        try {
            return transactionService.claim(command, generationAvailable);
        } catch (DataIntegrityViolationException retryException) {
            retryException.addSuppressed(exception);
            throw retryException;
        }
    }

    private CreateDiaryResult handleExistingClaim(DiaryCreationClaim claim) {
        if (claim.generationStatus() == GenerationStatus.PROCESSING) {
            throw new GenerationInProgressException();
        }
        if (claim.generationStatus() == GenerationStatus.FAILED) {
            throw new ComicGenerationFailedException(claim.errorCode());
        }
        ComicGenerationResult generationResult = new ComicGenerationResult(
                claim.generationId(),
                claim.generationStatus(),
                claim.title(),
                claim.imageObjectKey(),
                claim.completedAt(),
                false
        );
        return createResult(claim, generationResult, false);
    }

    private GenerateComicCommand createGenerationCommand(
            CreateDiaryCommand command,
            DiaryCreationClaim claim
    ) {
        return new GenerateComicCommand(
                command.userId(),
                claim.diaryId(),
                claim.diaryDate(),
                claim.sourceText(),
                command.idempotencyKey()
        );
    }

    private CreateDiaryResult createResult(
            DiaryCreationClaim claim,
            ComicGenerationResult generationResult,
            boolean newlyCreated
    ) {
        return new CreateDiaryResult(
                claim.diaryId(),
                claim.diaryDate(),
                claim.sourceText(),
                claim.createdAt(),
                new DiaryGenerationResult(
                        generationResult.generationId(),
                        generationResult.status(),
                        generationResult.title(),
                        generationResult.imageObjectKey(),
                        generationResult.completedAt()
                ),
                claim.usage(),
                newlyCreated
        );
    }
}
