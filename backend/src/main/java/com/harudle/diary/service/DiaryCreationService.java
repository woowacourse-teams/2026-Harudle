package com.harudle.diary.service;

import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.CreateDiaryResult;
import com.harudle.diary.service.dto.DiaryGenerationResult;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.domain.GenerationUsage;
import com.harudle.generation.service.DiaryGenerationExecutor;
import com.harudle.generation.service.dto.CompletedDiaryGeneration;
import com.harudle.generation.service.dto.GenerateDiaryImageCommand;
import com.harudle.generation.service.exception.DiaryGenerationFailedException;
import com.harudle.generation.service.exception.GenerationInProgressException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class DiaryCreationService {

    private final MemberDiaryCreationTransactionService memberTransactionService;
    private final DiaryGenerationExecutor generationExecutor;

    DiaryCreationService(
            MemberDiaryCreationTransactionService memberTransactionService,
            DiaryGenerationExecutor generationExecutor
    ) {
        this.memberTransactionService = memberTransactionService;
        this.generationExecutor = generationExecutor;
    }

    public CreateDiaryResult create(CreateDiaryCommand command) {
        MemberDiaryCreationClaim memberClaim = claim(command, generationExecutor.isConfigured());
        DiaryCreationClaim claim = memberClaim.claim();
        if (!claim.newlyCreated()) {
            return handleExistingClaim(claim, memberClaim.usage());
        }
        CompletedDiaryGeneration generationResult = generationExecutor.generate(
                createGenerationCommand(command, claim),
                claim.generationId()
        );
        return createResult(claim, generationResult, memberClaim.usage(), true);
    }

    private MemberDiaryCreationClaim claim(CreateDiaryCommand command, boolean generationAvailable) {
        try {
            return memberTransactionService.claim(command, generationAvailable);
        } catch (DataIntegrityViolationException exception) {
            return memberTransactionService.findExistingClaim(command)
                    .orElseThrow(() -> exception);
        }
    }

    private CreateDiaryResult handleExistingClaim(
            DiaryCreationClaim claim,
            GenerationUsage usage
    ) {
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
                    usage,
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
            GenerationUsage usage,
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
                usage,
                newlyCreated
        );
    }
}
