package com.harudle.diary.service;

import com.harudle.diary.service.dto.CreateGuestDiaryCommand;
import com.harudle.diary.service.dto.CreateGuestDiaryResult;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class GuestDiaryCreationService {

    private final GuestDiaryCreationTransactionService transactionService;
    private final DiaryCreationExecutionService executionService;

    GuestDiaryCreationService(
            GuestDiaryCreationTransactionService transactionService,
            DiaryCreationExecutionService executionService
    ) {
        this.transactionService = transactionService;
        this.executionService = executionService;
    }

    public CreateGuestDiaryResult create(
            String rawSessionToken,
            CreateGuestDiaryCommand command
    ) {
        GuestDiaryCreationClaim guestClaim = claim(
                rawSessionToken,
                command,
                executionService.isGenerationAvailable()
        );
        DiaryCreationExecution execution = executionService.execute(
                guestClaim.command(),
                guestClaim.claim()
        );
        return new CreateGuestDiaryResult(
                execution.diaryId(),
                execution.diaryDate(),
                execution.sourceText(),
                execution.createdAt(),
                execution.generation(),
                execution.newlyCreated()
        );
    }

    private GuestDiaryCreationClaim claim(
            String rawSessionToken,
            CreateGuestDiaryCommand command,
            boolean generationAvailable
    ) {
        try {
            return transactionService.claim(rawSessionToken, command, generationAvailable);
        } catch (DataIntegrityViolationException exception) {
            Optional<GuestDiaryCreationClaim> existingClaim =
                    transactionService.findExistingClaim(rawSessionToken, command);
            return existingClaim.orElseThrow(() -> exception);
        }
    }
}
