package com.harudle.generation.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "generation_prompts")
public class GenerationPrompt {

    private static final int MAX_IMAGE_ASSET_OBJECT_KEY_BYTES = 1024;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "storyboard_prompt_text", nullable = false, columnDefinition = "TEXT")
    private String storyboardPromptText;

    @Column(name = "image_style_prompt_text", nullable = false, columnDefinition = "TEXT")
    private String imageStylePromptText;

    @Column(name = "image_asset_object_key", nullable = false, length = 1024)
    private String imageAssetObjectKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GenerationPrompt() {
    }

    public GenerationPrompt(
            String storyboardPromptText,
            String imageStylePromptText,
            String imageAssetObjectKey
    ) {
        this.storyboardPromptText = normalizeRequired(storyboardPromptText, "스토리보드 프롬프트");
        this.imageStylePromptText = normalizeRequired(imageStylePromptText, "이미지 스타일 프롬프트");
        this.imageAssetObjectKey = normalizeImageAssetObjectKey(imageAssetObjectKey);
    }

    public Long getId() {
        return id;
    }

    public String getStoryboardPromptText() {
        return storyboardPromptText;
    }

    public String getImageStylePromptText() {
        return imageStylePromptText;
    }

    public String getImageAssetObjectKey() {
        return imageAssetObjectKey;
    }

    private static String normalizeImageAssetObjectKey(String imageAssetObjectKey) {
        String normalized = normalizeRequired(imageAssetObjectKey, "이미지 에셋 Object Key");
        validateImageAssetObjectKeyLength(normalized);
        return normalized;
    }

    private static String normalizeRequired(String value, String fieldName) {
        validateRequired(value, fieldName);
        return value.strip();
    }

    private static void validateImageAssetObjectKeyLength(String imageAssetObjectKey) {
        int byteLength = imageAssetObjectKey.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength > MAX_IMAGE_ASSET_OBJECT_KEY_BYTES) {
            throw new IllegalArgumentException("이미지 에셋 Object Key는 UTF-8 기준 1,024바이트 이하여야 합니다.");
        }
    }

    private static void validateRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은(는) 필수입니다.");
        }
    }
}
