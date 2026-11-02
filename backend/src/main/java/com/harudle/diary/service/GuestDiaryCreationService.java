package com.harudle.diary.service;

import com.harudle.diary.service.dto.CreateGuestDiaryCommand;
import com.harudle.diary.service.dto.CreateGuestDiaryResult;
import com.harudle.generation.diary.service.DiaryGenerationExecutor;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class GuestDiaryCreationService {

    private final GuestDiaryCreationTransactionService transactionService;
    private final DiaryGenerationExecutor generationExecutor;

    GuestDiaryCreationService(
            GuestDiaryCreationTransactionService transactionService,
            DiaryGenerationExecutor generationExecutor
    ) {
        this.transactionService = transactionService;
        this.generationExecutor = generationExecutor;
    }

    public CreateGuestDiaryResult create(
            String rawSessionToken,
            CreateGuestDiaryCommand command
    ) {
        GuestDiaryCreationClaim guestClaim = enqueue(
                rawSessionToken,
                command,
                generationExecutor.isConfigured()
        );
        DiaryCreationClaim claim = guestClaim.claim();
        return new CreateGuestDiaryResult(
                claim.diaryId(),
                claim.diaryDate(),
                claim.sourceText(),
                claim.createdAt(),
                claim.toGenerationResult(),
                claim.newlyCreated()
        );
    }

    private GuestDiaryCreationClaim enqueue(
            String rawSessionToken,
            CreateGuestDiaryCommand command,
            boolean generationAvailable
    ) {
        try {
            return transactionService.enqueue(rawSessionToken, command, generationAvailable);
        } catch (DataIntegrityViolationException exception) {
            Optional<GuestDiaryCreationClaim> existingClaim =
                    transactionService.findExistingClaim(rawSessionToken, command);
            return existingClaim.orElseThrow(() -> exception);
        }
    }
}
