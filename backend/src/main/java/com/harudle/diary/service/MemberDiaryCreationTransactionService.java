package com.harudle.diary.service;

import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.generation.usage.domain.GenerationUsage;
import com.harudle.generation.usage.service.GenerationUsageService;
import java.util.Optional;
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
        DiaryCreationClaim claim = claimService.claim(command, generationAvailable);
        GenerationUsage usage = applyUsagePolicy(command, claim);
        return new MemberDiaryCreationClaim(claim, usage);
    }

    @Transactional
    Optional<MemberDiaryCreationClaim> findExistingClaim(CreateDiaryCommand command) {
        return claimService.findExistingClaim(command)
                .map(claim -> new MemberDiaryCreationClaim(
                        claim,
                        generationUsageService.getTodayUsage(command.userId())
                ));
    }

    private GenerationUsage applyUsagePolicy(
            CreateDiaryCommand command,
            DiaryCreationClaim claim
    ) {
        if (claim.newlyCreated()) {
            return generationUsageService.incrementTodayUsage(command.userId());
        }
        return generationUsageService.getTodayUsage(command.userId());
    }
}
