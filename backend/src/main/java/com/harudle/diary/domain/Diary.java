package com.harudle.diary.domain;

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

    private static final int MAX_SOURCE_TEXT_LENGTH = 300;

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "diary_date", nullable = false)
    private LocalDate diaryDate;

    @Column(name = "source_text", nullable = false, length = MAX_SOURCE_TEXT_LENGTH)
    private String sourceText;

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

    private Diary(UUID id, UUID userId, LocalDate diaryDate, String sourceText) {
        validateId(id);
        validateUserId(userId);
        validateDiaryDate(diaryDate);

        this.id = id;
        this.userId = userId;
        this.diaryDate = diaryDate;
        this.sourceText = normalizeSourceText(sourceText);
    }

    public static Diary create(UUID userId, LocalDate diaryDate, String sourceText) {
        return new Diary(UUID.randomUUID(), userId, diaryDate, sourceText);
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

    public String getSourceText() {
        return sourceText;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
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
        String normalizedSourceText = sourceText.strip();
        int sourceTextLength = normalizedSourceText.codePointCount(0, normalizedSourceText.length());
        if (sourceTextLength > MAX_SOURCE_TEXT_LENGTH) {
            throw new IllegalArgumentException("일기 내용은 300자 이하여야 합니다.");
        }
    }

    private static void validateDeletedAt(Instant deletedAt) {
        if (deletedAt == null) {
            throw new IllegalArgumentException("일기 삭제 시각은 필수입니다.");
        }
    }
}
