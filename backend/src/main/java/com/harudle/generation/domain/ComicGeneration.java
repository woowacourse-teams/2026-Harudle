package com.harudle.generation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "comic_generations")
public class ComicGeneration {

    private static final int MAX_IMAGE_OBJECT_KEY_BYTES = 1024;
    private static final Pattern REQUEST_FINGERPRINT_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    @Id
    private UUID id;

    @Column(name = "diary_id", nullable = false)
    private UUID diaryId;

    @Column(name = "prompt_id", nullable = false)
    private Long generationPromptId;

    @Column(name = "idempotency_key", nullable = false)
    private UUID idempotencyKey;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GenerationStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "storyboard", columnDefinition = "jsonb")
    private Storyboard storyboard;

    @Column(name = "title", length = 100)
    private String title;

    @Column(name = "image_object_key", length = 1024)
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

    protected ComicGeneration() {
    }

    private ComicGeneration(
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

    public static ComicGeneration start(
            UUID diaryId,
            Long generationPromptId,
            UUID idempotencyKey,
            String requestFingerprint
    ) {
        return new ComicGeneration(
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

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public GenerationStatus getStatus() {
        return status;
    }

    public Storyboard getStoryboard() {
        return storyboard;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
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

    public void interrupt(Instant completedAt) {
        fail(GenerationErrorCode.GENERATION_INTERRUPTED, completedAt);
    }

    private static String normalizeRequestFingerprint(String requestFingerprint) {
        validateRequestFingerprint(requestFingerprint);
        return requestFingerprint.strip();
    }

    private static String normalizeImageObjectKey(String imageObjectKey) {
        validateImageObjectKey(imageObjectKey);
        return imageObjectKey.strip();
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
            throw new IllegalArgumentException("요청 지문은 소문자 SHA-256 64자리여야 합니다.");
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

    private static void validateImageObjectKey(String imageObjectKey) {
        if (imageObjectKey == null || imageObjectKey.isBlank()) {
            throw new IllegalArgumentException("이미지 Object Key는 필수입니다.");
        }
        int byteLength = imageObjectKey.strip().getBytes(StandardCharsets.UTF_8).length;
        if (byteLength > MAX_IMAGE_OBJECT_KEY_BYTES) {
            throw new IllegalArgumentException("이미지 Object Key는 UTF-8 기준 1,024바이트 이하여야 합니다.");
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
}
