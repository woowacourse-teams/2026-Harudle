package com.harudle.diary.service;

import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.CreateDiaryResult;
import com.harudle.generation.diary.service.DiaryGenerationExecutor;
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
        MemberDiaryCreationClaim memberClaim = enqueue(
                command,
                generationExecutor.isConfigured()
        );
        DiaryCreationClaim claim = memberClaim.claim();
        return new CreateDiaryResult(
                claim.diaryId(),
                claim.diaryDate(),
                claim.sourceText(),
                claim.createdAt(),
                claim.toGenerationResult(),
                memberClaim.usage(),
                claim.newlyCreated()
        );
    }

    private MemberDiaryCreationClaim enqueue(CreateDiaryCommand command, boolean generationAvailable) {
        try {
            return memberTransactionService.enqueue(command, generationAvailable);
        } catch (DataIntegrityViolationException exception) {
            return memberTransactionService.findExistingClaim(command)
                    .orElseThrow(() -> exception);
        }
    }
}
