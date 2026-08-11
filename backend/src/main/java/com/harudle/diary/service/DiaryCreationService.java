package com.harudle.diary.service;

import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.CreateDiaryResult;
import com.harudle.diary.service.dto.DiaryGenerationResult;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.service.ComicGenerationExecutor;
import com.harudle.generation.service.dto.CompletedComicGeneration;
import com.harudle.generation.service.dto.GenerateComicCommand;
import com.harudle.generation.service.exception.ComicGenerationFailedException;
import com.harudle.generation.service.exception.GenerationInProgressException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class DiaryCreationService {

    private final DiaryCreationTransactionService transactionService;
    private final ComicGenerationExecutor generationExecutor;

    DiaryCreationService(
            DiaryCreationTransactionService transactionService,
            ComicGenerationExecutor generationExecutor
    ) {
        this.transactionService = transactionService;
        this.generationExecutor = generationExecutor;
    }

    public CreateDiaryResult create(CreateDiaryCommand command) {
        DiaryCreationClaim claim = claim(command, generationExecutor.isConfigured());
        if (!claim.newlyCreated()) {
            return handleExistingClaim(claim);
        }
        CompletedComicGeneration generationResult = generationExecutor.generate(
                createGenerationCommand(command, claim),
                claim.generationId()
        );
        return createResult(claim, generationResult, true);
    }

    private DiaryCreationClaim claim(CreateDiaryCommand command, boolean generationAvailable) {
        try {
            return transactionService.claim(command, generationAvailable);
        } catch (DataIntegrityViolationException exception) {
            return transactionService.findExistingClaim(command)
                    .orElseThrow(() -> exception);
        }
    }

    private CreateDiaryResult handleExistingClaim(DiaryCreationClaim claim) {
        return switch (claim.generationStatus()) {
            case PROCESSING -> throw new GenerationInProgressException();
            case FAILED -> throw new ComicGenerationFailedException(claim.errorCode());
            case SUCCEEDED -> createResult(
                    claim,
                    new CompletedComicGeneration(
                            claim.generationId(),
                            claim.title(),
                            claim.imageObjectKey(),
                            claim.completedAt()
                    ),
                    false
            );
        };
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
            CompletedComicGeneration generationResult,
            boolean newlyCreated
    ) {
        return new CreateDiaryResult(
                claim.diaryId(),
                claim.diaryDate(),
                claim.sourceText(),
                claim.createdAt(),
                new DiaryGenerationResult(
                        generationResult.generationId(),
                        GenerationStatus.SUCCEEDED,
                        generationResult.title(),
                        generationResult.imageObjectKey(),
                        generationResult.completedAt()
                ),
                claim.usage(),
                newlyCreated
        );
    }
}
