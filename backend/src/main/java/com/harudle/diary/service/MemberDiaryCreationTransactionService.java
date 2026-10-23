package com.harudle.diary.service;

import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.usage.domain.GenerationUsage;
import com.harudle.generation.usage.service.GenerationUsageService;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class MemberDiaryCreationTransactionService {

    private final DiaryCreationClaimService claimService;
    private final GenerationUsageService generationUsageService;

    MemberDiaryCreationTransactionService(
            DiaryCreationClaimService claimService,
            GenerationUsageService generationUsageService
    ) {
        this.claimService = claimService;
        this.generationUsageService = generationUsageService;
    }

    @Transactional
    MemberDiaryCreationClaim claim(CreateDiaryCommand command, boolean generationAvailable) {
        LocalDate usageDate = generationUsageService.getCurrentUsageDate();
        DiaryCreationClaim claim = claimService.claim(command, generationAvailable, usageDate);
        GenerationUsage usage = applyUsagePolicy(command, claim, usageDate);
        return new MemberDiaryCreationClaim(claim, usage);
    }

    @Transactional
    MemberDiaryCreationClaim enqueue(CreateDiaryCommand command, boolean generationAvailable) {
        return enqueue(command, generationAvailable, generationId -> {});
    }

    @Transactional
    MemberDiaryCreationClaim enqueue(
            CreateDiaryCommand command,
            boolean generationAvailable,
            Consumer<UUID> onPendingGeneration
    ) {
        LocalDate usageDate = generationUsageService.getCurrentUsageDate();
        DiaryCreationClaim claim = claimService.enqueue(command, generationAvailable, usageDate);
        GenerationUsage usage = applyUsagePolicy(command, claim, usageDate);
        registerPendingGeneration(claim, onPendingGeneration);
        return new MemberDiaryCreationClaim(claim, usage);
    }

    @Transactional
    Optional<MemberDiaryCreationClaim> findExistingClaim(CreateDiaryCommand command) {
        return findExistingClaim(command, generationId -> {});
    }

    @Transactional
    Optional<MemberDiaryCreationClaim> findExistingClaim(
            CreateDiaryCommand command,
            Consumer<UUID> onPendingGeneration
    ) {
        return claimService.findExistingClaim(command)
                .map(claim -> {
                    GenerationUsage usage = generationUsageService.getTodayUsage(command.userId());
                    registerPendingGeneration(claim, onPendingGeneration);
                    return new MemberDiaryCreationClaim(claim, usage);
                });
    }

    private void registerPendingGeneration(DiaryCreationClaim claim, Consumer<UUID> onPendingGeneration) {
        if (claim.generationStatus() == GenerationStatus.PENDING) {
            onPendingGeneration.accept(claim.generationId());
        }
    }

    private GenerationUsage applyUsagePolicy(
            CreateDiaryCommand command,
            DiaryCreationClaim claim,
            LocalDate usageDate
    ) {
        if (claim.newlyCreated()) {
            return generationUsageService.incrementUsage(command.userId(), usageDate);
        }
        return generationUsageService.getTodayUsage(command.userId());
    }
}
