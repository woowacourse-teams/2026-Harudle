package com.harudle.diary.service;

import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.CreateDiaryResult;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class DiaryCreationService {

    private final MemberDiaryCreationTransactionService memberTransactionService;
    private final DiaryCreationExecutionService executionService;

    DiaryCreationService(
            MemberDiaryCreationTransactionService memberTransactionService,
            DiaryCreationExecutionService executionService
    ) {
        this.memberTransactionService = memberTransactionService;
        this.executionService = executionService;
    }

    public CreateDiaryResult create(CreateDiaryCommand command) {
        MemberDiaryCreationClaim memberClaim = claim(
                command,
                executionService.isGenerationAvailable()
        );
        DiaryCreationExecution execution = executionService.execute(command, memberClaim.claim());
        return new CreateDiaryResult(
                execution.diaryId(),
                execution.diaryDate(),
                execution.sourceText(),
                execution.createdAt(),
                execution.generation(),
                memberClaim.usage(),
                execution.newlyCreated()
        );
    }

    private MemberDiaryCreationClaim claim(CreateDiaryCommand command, boolean generationAvailable) {
        try {
            return memberTransactionService.claim(command, generationAvailable);
        } catch (DataIntegrityViolationException exception) {
            return memberTransactionService.findExistingClaim(command)
                    .orElseThrow(() -> exception);
        }
    }
}
