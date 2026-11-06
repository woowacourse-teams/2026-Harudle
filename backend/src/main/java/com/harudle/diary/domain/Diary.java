package com.harudle.diary.domain;

import com.harudle.common.validation.UnicodeText;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "diaries")
public class Diary {

    public static final int MAX_SOURCE_TEXT_CODE_POINTS = 300;

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "diary_date", nullable = false)
    private LocalDate diaryDate;

    @Column(name = "source_text", nullable = false, columnDefinition = "text")
    private String sourceText;

    @Column(name = "source_text_version", nullable = false)
    private int sourceTextVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Diary() {
    }

    private Diary(UUID id, UUID userId, LocalDate diaryDate, String sourceText, DiarySourceTextCipher cipher) {
        validateId(id);
        validateUserId(userId);
        validateDiaryDate(diaryDate);

        this.id = id;
        this.userId = userId;
        this.diaryDate = diaryDate;
        this.sourceText = cipher.encrypt(id, userId, normalizeSourceText(sourceText));
        this.sourceTextVersion = DiarySourceTextCipher.CURRENT_VERSION;
    }

    public static Diary create(UUID userId, LocalDate diaryDate, String sourceText, DiarySourceTextCipher cipher) {
        return new Diary(UUID.randomUUID(), userId, diaryDate, sourceText, cipher);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public LocalDate getDiaryDate() {
        return diaryDate;
    }

    public String decryptSourceText(DiarySourceTextCipher cipher) {
        return cipher.decrypt(id, userId, sourceText, sourceTextVersion);
    }

    public String protectRequestFingerprint(DiarySourceTextCipher cipher, String fingerprint) {
        return cipher.protectRequestFingerprint(id, userId, sourceText, sourceTextVersion, fingerprint);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isOwnedBy(UUID userId) {
        return this.userId.equals(userId);
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void delete(Instant deletedAt) {
        if (isDeleted()) {
            return;
        }
        validateDeletedAt(deletedAt);
        this.deletedAt = deletedAt;
    }

    public void transferOwnership(UUID currentOwnerId, UUID newOwnerId, DiarySourceTextCipher cipher) {
        validateUserId(currentOwnerId);
        validateUserId(newOwnerId);
        if (currentOwnerId.equals(newOwnerId)) {
            throw new IllegalArgumentException("같은 사용자에게 일기를 옮길 수 없습니다.");
        }
        if (isDeleted()) {
            throw new IllegalStateException("삭제된 일기는 옮길 수 없습니다.");
        }
        if (!isOwnedBy(currentOwnerId)) {
            throw new IllegalStateException("현재 작성자의 일기만 옮길 수 있습니다.");
        }
        // The owner is authenticated with the ciphertext. Prepare the new ciphertext before changing either field.
        String plaintext = decryptSourceText(cipher);
        String transferredSourceText = cipher.encrypt(id, newOwnerId, plaintext);
        this.sourceText = transferredSourceText;
        this.userId = newOwnerId;
    }

    public static String normalizeSourceText(String sourceText) {
        validateSourceText(sourceText);
        return sourceText.strip();
    }

    private static void validateId(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("일기 ID는 필수입니다.");
        }
    }

    private static void validateUserId(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("사용자 ID는 필수입니다.");
        }
    }

    private static void validateDiaryDate(LocalDate diaryDate) {
        if (diaryDate == null) {
            throw new IllegalArgumentException("일기 날짜는 필수입니다.");
        }
    }

    private static void validateSourceText(String sourceText) {
        if (sourceText == null || sourceText.isBlank()) {
            throw new IllegalArgumentException("일기 내용은 필수입니다.");
        }
        if (!UnicodeText.isWellFormed(sourceText)) {
            throw new IllegalArgumentException("일기 내용에 올바르지 않은 문자가 포함되어 있습니다.");
        }
        String normalizedSourceText = sourceText.strip();
        int sourceTextLength = normalizedSourceText.codePointCount(0, normalizedSourceText.length());
        if (sourceTextLength > MAX_SOURCE_TEXT_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "일기 내용은 %d자 이하여야 합니다.".formatted(MAX_SOURCE_TEXT_CODE_POINTS)
            );
        }
    }

    private static void validateDeletedAt(Instant deletedAt) {
        if (deletedAt == null) {
            throw new IllegalArgumentException("일기 삭제 시각은 필수입니다.");
        }
    }
}
