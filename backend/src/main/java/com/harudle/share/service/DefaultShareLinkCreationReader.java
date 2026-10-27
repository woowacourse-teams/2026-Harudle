package com.harudle.share.service;

import com.harudle.diary.repository.DiaryRepository;
import com.harudle.diary.repository.DiarySnapshot;
import com.harudle.diary.service.exception.DiaryAccessDeniedException;
import com.harudle.diary.service.exception.DiaryNotFoundException;
import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DefaultShareLinkCreationReader implements ShareLinkCreationReader {

    private final DiaryRepository diaryRepository;
    private final DiaryGenerationRepository diaryGenerationRepository;

    public DefaultShareLinkCreationReader(
            DiaryRepository diaryRepository,
            DiaryGenerationRepository diaryGenerationRepository
    ) {
        this.diaryRepository = diaryRepository;
        this.diaryGenerationRepository = diaryGenerationRepository;
    }

    @Override
    public ShareLinkCreationInfo read(UUID userId, UUID diaryId) {
        DiarySnapshot diary = diaryRepository.findActiveSnapshotById(diaryId)
                .orElseThrow(DiaryNotFoundException::new);
        if (!diary.isOwnedBy(userId)) {
            throw new DiaryAccessDeniedException();
        }

        DiaryGeneration generation = diaryGenerationRepository.findByDiaryIdForUpdate(diaryId)
                .orElseThrow(DiaryNotFoundException::new);
        return new ShareLinkCreationInfo(
                generation.getId(),
                generation.getStatus()
        );
    }
}
