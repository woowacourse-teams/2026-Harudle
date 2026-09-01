package com.harudle.generation.diary.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "diary_generations")
public class DiaryGeneration {

    private static final int REQUEST_FINGERPRINT_HEX_LENGTH = 64;
    private static final Pattern REQUEST_FINGERPRINT_PATTERN = Pattern.compile(
            "^[0-9a-f]{" + REQUEST_FINGERPRINT_HEX_LENGTH + "}$"
    );

    @Id
    private UUID id;

    @Column(name = "diary_id", nullable = false)
    private UUID diaryId;

    @Column(name = "prompt_id", nullable = false)
    private Long generationPromptId;

    @Column(name = "idempotency_key", nullable = false)
    private UUID idempotencyKey;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_fingerprint", nullable = false, length = REQUEST_FINGERPRINT_HEX_LENGTH)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GenerationStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "storyboard", columnDefinition = "jsonb")
    private Storyboard storyboard;

    @Column(name = "title", length = Storyboard.MAX_TITLE_LENGTH)
    private String title;

    @Column(name = "image_object_key", length = ImageObjectKeyPolicy.MAX_UTF8_BYTES)
    private String imageObjectKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_code", length = 50)
    private GenerationErrorCode errorCode;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected DiaryGeneration() {
    }

    private DiaryGeneration(
            UUID id,
            UUID diaryId,
            Long generationPromptId,
            UUID idempotencyKey,
            String requestFingerprint
    ) {
        validateId(id);
        validateDiaryId(diaryId);
        validateGenerationPromptId(generationPromptId);
        validateIdempotencyKey(idempotencyKey);

        this.id = id;
        this.diaryId = diaryId;
        this.generationPromptId = generationPromptId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = normalizeRequestFingerprint(requestFingerprint);
        this.status = GenerationStatus.PROCESSING;
    }

    public static DiaryGeneration start(
            UUID diaryId,
            Long generationPromptId,
            UUID idempotencyKey,
            String requestFingerprint
    ) {
        return new DiaryGeneration(
                UUID.randomUUID(),
                diaryId,
                generationPromptId,
                idempotencyKey,
                requestFingerprint
        );
    }

    public UUID getId() {
        return id;
    }

    public UUID getDiaryId() {
        return diaryId;
    }

    public Long getGenerationPromptId() {
        return generationPromptId;
    }

    public GenerationStatus getStatus() {
        return status;
    }

    public String getTitle() {
        return title;
    }

    public String getImageObjectKey() {
        return imageObjectKey;
    }

    public GenerationErrorCode getErrorCode() {
        return errorCode;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public boolean hasSameRequestFingerprint(String requestFingerprint) {
        return this.requestFingerprint.equals(normalizeRequestFingerprint(requestFingerprint));
    }

    public boolean usesImageObjectKey(String candidate) {
        return imageObjectKey != null && imageObjectKey.equals(candidate);
    }

    public boolean matchesExecutableClaim(
            UUID diaryId,
            UUID idempotencyKey,
            String requestFingerprint
    ) {
        validateDiaryId(diaryId);
        validateIdempotencyKey(idempotencyKey);
        return this.diaryId.equals(diaryId)
                && this.idempotencyKey.equals(idempotencyKey)
                && hasSameRequestFingerprint(requestFingerprint)
                && status == GenerationStatus.PROCESSING;
    }

    public void succeed(Storyboard storyboard, String imageObjectKey, Instant completedAt) {
        validateProcessingStatus();
        validateStoryboard(storyboard);
        validateCompletedAt(completedAt);
        String normalizedImageObjectKey = normalizeImageObjectKey(imageObjectKey);

        this.storyboard = storyboard;
        this.title = storyboard.title();
        this.imageObjectKey = normalizedImageObjectKey;
        this.errorCode = null;
        this.completedAt = completedAt;
        this.status = GenerationStatus.SUCCEEDED;
    }

    public void fail(GenerationErrorCode errorCode, Instant completedAt) {
        validateProcessingStatus();
        validateErrorCode(errorCode);
        validateCompletedAt(completedAt);

        this.errorCode = errorCode;
        this.completedAt = completedAt;
        this.status = GenerationStatus.FAILED;
    }

    void interrupt(Instant completedAt) {
        fail(GenerationErrorCode.GENERATION_INTERRUPTED, completedAt);
    }

    public void interruptIfStale(Instant currentTime, Duration processingTimeout) {
        validateCurrentTime(currentTime);
        validateProcessingTimeout(processingTimeout);
        if (status != GenerationStatus.PROCESSING || updatedAt == null) {
            return;
        }
        Instant staleCutoff = currentTime.minus(processingTimeout);
        if (!updatedAt.isAfter(staleCutoff)) {
            interrupt(currentTime);
        }
    }

    private static String normalizeRequestFingerprint(String requestFingerprint) {
        validateRequestFingerprint(requestFingerprint);
        return requestFingerprint.strip();
    }

    private static String normalizeImageObjectKey(String imageObjectKey) {
        return ImageObjectKeyPolicy.normalizeRequired(imageObjectKey, "이미지 Object Key");
    }

    private static void validateId(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("생성 ID는 필수입니다.");
        }
    }

    private static void validateDiaryId(UUID diaryId) {
        if (diaryId == null) {
            throw new IllegalArgumentException("일기 ID는 필수입니다.");
        }
    }

    private static void validateGenerationPromptId(Long generationPromptId) {
        if (generationPromptId == null || generationPromptId <= 0) {
            throw new IllegalArgumentException("생성 프롬프트 ID는 양수여야 합니다.");
        }
    }

    private static void validateIdempotencyKey(UUID idempotencyKey) {
        if (idempotencyKey == null) {
            throw new IllegalArgumentException("멱등성 키는 필수입니다.");
        }
    }

    private static void validateRequestFingerprint(String requestFingerprint) {
        if (requestFingerprint == null
                || !REQUEST_FINGERPRINT_PATTERN.matcher(requestFingerprint.strip()).matches()) {
            String message = "요청 지문은 소문자 SHA-256 %d자리여야 합니다."
                    .formatted(REQUEST_FINGERPRINT_HEX_LENGTH);
            throw new IllegalArgumentException(message);
        }
    }

    private void validateProcessingStatus() {
        if (status != GenerationStatus.PROCESSING) {
            throw new IllegalStateException("처리 중인 생성 작업만 완료할 수 있습니다.");
        }
    }

    private static void validateStoryboard(Storyboard storyboard) {
        if (storyboard == null) {
            throw new IllegalArgumentException("스토리보드는 필수입니다.");
        }
    }

    private static void validateErrorCode(GenerationErrorCode errorCode) {
        if (errorCode == null) {
            throw new IllegalArgumentException("생성 오류 코드는 필수입니다.");
        }
    }

    private static void validateCompletedAt(Instant completedAt) {
        if (completedAt == null) {
            throw new IllegalArgumentException("완료 시각은 필수입니다.");
        }
    }

    private static void validateCurrentTime(Instant currentTime) {
        if (currentTime == null) {
            throw new IllegalArgumentException("현재 시각은 필수입니다.");
        }
    }

    private static void validateProcessingTimeout(Duration processingTimeout) {
        if (processingTimeout == null || processingTimeout.isZero() || processingTimeout.isNegative()) {
            throw new IllegalArgumentException("생성 처리 제한 시간은 양수여야 합니다.");
        }
    }
}
