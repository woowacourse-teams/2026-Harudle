package com.harudle.diary.service;

import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.CreateDiaryResult;
import com.harudle.diary.service.dto.DiaryGenerationResult;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.service.DiaryGenerationExecutor;
import com.harudle.generation.service.dto.CompletedDiaryGeneration;
import com.harudle.generation.service.dto.GenerateDiaryImageCommand;
import com.harudle.generation.service.exception.DiaryGenerationFailedException;
import com.harudle.generation.service.exception.GenerationInProgressException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class DiaryCreationService {

    private final DiaryCreationTransactionService transactionService;
    private final DiaryGenerationExecutor generationExecutor;

    DiaryCreationService(
            DiaryCreationTransactionService transactionService,
            DiaryGenerationExecutor generationExecutor
    ) {
        this.transactionService = transactionService;
        this.generationExecutor = generationExecutor;
    }

    public CreateDiaryResult create(CreateDiaryCommand command) {
        DiaryCreationClaim claim = claim(command, generationExecutor.isConfigured());
        if (!claim.newlyCreated()) {
            return handleExistingClaim(claim);
        }
        CompletedDiaryGeneration generationResult = generationExecutor.generate(
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
            case FAILED -> throw new DiaryGenerationFailedException(claim.errorCode());
            case SUCCEEDED -> createResult(
                    claim,
                    new CompletedDiaryGeneration(
                            claim.generationId(),
                            claim.title(),
                            claim.imageObjectKey(),
                            claim.completedAt()
                    ),
                    false
            );
        };
    }

    private GenerateDiaryImageCommand createGenerationCommand(
            CreateDiaryCommand command,
            DiaryCreationClaim claim
    ) {
        return new GenerateDiaryImageCommand(
                command.userId(),
                claim.diaryId(),
                claim.diaryDate(),
                claim.sourceText(),
                command.idempotencyKey()
        );
    }

    private CreateDiaryResult createResult(
            DiaryCreationClaim claim,
            CompletedDiaryGeneration generationResult,
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
