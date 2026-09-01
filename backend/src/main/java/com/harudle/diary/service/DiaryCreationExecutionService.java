package com.harudle.diary.service;

import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.DiaryGenerationResult;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.service.DiaryGenerationExecutor;
import com.harudle.generation.diary.service.dto.CompletedDiaryGeneration;
import com.harudle.generation.diary.service.dto.GenerateDiaryImageCommand;
import com.harudle.generation.diary.service.exception.DiaryGenerationFailedException;
import com.harudle.generation.diary.service.exception.GenerationInProgressException;
import org.springframework.stereotype.Service;

@Service
class DiaryCreationExecutionService {

    private final DiaryGenerationExecutor generationExecutor;

    DiaryCreationExecutionService(DiaryGenerationExecutor generationExecutor) {
        this.generationExecutor = generationExecutor;
    }

    boolean isGenerationAvailable() {
        return generationExecutor.isConfigured();
    }

    DiaryCreationExecution execute(
            CreateDiaryCommand command,
            DiaryCreationClaim claim
    ) {
        if (!claim.newlyCreated()) {
            return handleExistingClaim(claim);
        }
        CompletedDiaryGeneration generationResult = generationExecutor.generate(
                createGenerationCommand(command, claim),
                claim.generationId()
        );
        return createResult(claim, generationResult, true);
    }

    private DiaryCreationExecution handleExistingClaim(DiaryCreationClaim claim) {
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

    private DiaryCreationExecution createResult(
            DiaryCreationClaim claim,
            CompletedDiaryGeneration generationResult,
            boolean newlyCreated
    ) {
        return new DiaryCreationExecution(
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
                newlyCreated
        );
    }
}
