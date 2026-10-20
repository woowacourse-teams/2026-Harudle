package com.harudle.diary.service;

import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.CreateDiaryResult;
import com.harudle.generation.config.GenerationWorkerProperties;
import com.harudle.generation.loadtest.LoadTestGenerationRegistry;
import org.springframework.dao.DataIntegrityViolationException;

public final class LoadTestDiaryCreationService {

    private final MemberDiaryCreationTransactionService memberTransactionService;
    private final LoadTestGenerationRegistry generationRegistry;
    private final GenerationWorkerProperties workerProperties;

    LoadTestDiaryCreationService(
            MemberDiaryCreationTransactionService memberTransactionService,
            LoadTestGenerationRegistry generationRegistry,
            GenerationWorkerProperties workerProperties
    ) {
        this.memberTransactionService = memberTransactionService;
        this.generationRegistry = generationRegistry;
        this.workerProperties = workerProperties;
    }

    public CreateDiaryResult create(CreateDiaryCommand command) {
        MemberDiaryCreationClaim memberClaim = enqueue(
                command, workerProperties.enabled() && generationRegistry.isConfigured()
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

    public boolean isWorkerEnabled() {
        return workerProperties.enabled();
    }

    private MemberDiaryCreationClaim enqueue(CreateDiaryCommand command, boolean generationAvailable) {
        try {
            return memberTransactionService.enqueue(command, generationAvailable, generationRegistry::register);
        } catch (DataIntegrityViolationException exception) {
            return memberTransactionService.findExistingClaim(command, generationRegistry::register)
                    .orElseThrow(() -> exception);
        }
    }
}
