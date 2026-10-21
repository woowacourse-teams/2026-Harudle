package com.harudle.diary.service;

import com.harudle.diary.domain.Diary;
import com.harudle.diary.domain.DiarySourceTextCipher;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.generation.diary.service.port.RequestFingerprintProtector;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DefaultRequestFingerprintProtector implements RequestFingerprintProtector {

    private final DiaryRepository diaryRepository;
    private final DiarySourceTextCipher sourceTextCipher;

    public DefaultRequestFingerprintProtector(DiaryRepository diaryRepository, DiarySourceTextCipher sourceTextCipher) {
        this.diaryRepository = diaryRepository;
        this.sourceTextCipher = sourceTextCipher;
    }

    @Override
    public String protect(UUID diaryId, UUID userId, String fingerprint) {
        if (diaryId == null || userId == null) {
            throw invalidClaim();
        }
        Diary diary = diaryRepository.findById(diaryId).orElseThrow(DefaultRequestFingerprintProtector::invalidClaim);
        if (!diary.isOwnedBy(userId)) {
            throw invalidClaim();
        }
        return diary.protectRequestFingerprint(sourceTextCipher, fingerprint);
    }

    private static IllegalStateException invalidClaim() {
        return new IllegalStateException("생성 요청의 일기를 확인할 수 없습니다.");
    }
}
