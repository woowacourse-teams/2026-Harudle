package com.harudle.generation.diary.service;

import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PendingDiaryGenerationClaimService {

    private final DiaryGenerationRepository diaryGenerationRepository;

    public PendingDiaryGenerationClaimService(DiaryGenerationRepository diaryGenerationRepository) {
        this.diaryGenerationRepository = diaryGenerationRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<UUID> claim(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("가져올 생성 작업 수는 1 이상이어야 합니다.");
        }
        List<DiaryGeneration> generations = diaryGenerationRepository.findPendingForUpdateSkipLocked(batchSize);
        generations.forEach(DiaryGeneration::startProcessing);
        return generations.stream()
                .map(DiaryGeneration::getId)
                .toList();
    }
}
