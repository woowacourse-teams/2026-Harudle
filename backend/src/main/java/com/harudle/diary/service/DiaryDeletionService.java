package com.harudle.diary.service;

import com.harudle.diary.domain.Diary;
import com.harudle.diary.repository.DiaryDeletionRepository;
import com.harudle.diary.service.exception.DiaryAccessDeniedException;
import com.harudle.share.repository.ShareLinkDeletionRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiaryDeletionService {

    private final DiaryDeletionRepository diaryDeletionRepository;
    private final ShareLinkDeletionRepository shareLinkDeletionRepository;
    private final Clock clock;

    DiaryDeletionService(
            DiaryDeletionRepository diaryDeletionRepository,
            ShareLinkDeletionRepository shareLinkDeletionRepository,
            Clock clock
    ) {
        this.diaryDeletionRepository = diaryDeletionRepository;
        this.shareLinkDeletionRepository = shareLinkDeletionRepository;
        this.clock = clock;
    }

    @Transactional
    public void delete(UUID userId, UUID diaryId) {
        validateParameters(userId, diaryId);
        diaryDeletionRepository.findActiveById(diaryId)
                .ifPresent(diary -> deleteOwnedDiary(diary, userId, diaryId));
    }

    private void deleteOwnedDiary(Diary diary, UUID userId, UUID diaryId) {
        if (!diary.isOwnedBy(userId)) {
            throw new DiaryAccessDeniedException();
        }
        shareLinkDeletionRepository.deleteAllByDiaryId(diaryId);
        diary.delete(clock.instant());
    }

    private static void validateParameters(UUID userId, UUID diaryId) {
        if (userId == null) {
            throw new IllegalArgumentException("사용자 ID는 필수입니다.");
        }
        if (diaryId == null) {
            throw new IllegalArgumentException("일기 ID는 필수입니다.");
        }
    }
}
