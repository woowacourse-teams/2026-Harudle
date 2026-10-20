package com.harudle.diary.repository;

import com.harudle.diary.domain.DiarySourceTextCipher;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class DiarySnapshot {

    private final UUID id;
    private final UUID userId;
    private final LocalDate diaryDate;
    private final String sourceText;
    private final int sourceTextVersion;
    private final Instant createdAt;

    public DiarySnapshot(
            UUID id,
            UUID userId,
            LocalDate diaryDate,
            String sourceText,
            int sourceTextVersion,
            Instant createdAt
    ) {
        this.id = id;
        this.userId = userId;
        this.diaryDate = diaryDate;
        this.sourceText = sourceText;
        this.sourceTextVersion = sourceTextVersion;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public LocalDate diaryDate() {
        return diaryDate;
    }

    public String decryptSourceText(DiarySourceTextCipher cipher) {
        return cipher.decrypt(id, userId, sourceText, sourceTextVersion);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public boolean isOwnedBy(UUID requesterId) {
        return userId.equals(requesterId);
    }
}
