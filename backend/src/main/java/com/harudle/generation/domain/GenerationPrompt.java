package com.harudle.generation.domain;

import com.harudle.common.validation.TextValidator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "generation_prompts")
public class GenerationPrompt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "storyboard_prompt_text", nullable = false, columnDefinition = "TEXT")
    private String storyboardPromptText;

    @Column(name = "image_style_prompt_text", nullable = false, columnDefinition = "TEXT")
    private String imageStylePromptText;

    @Column(name = "image_asset_object_key", nullable = false, length = ImageObjectKeyPolicy.MAX_UTF8_BYTES)
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
        this.storyboardPromptText = TextValidator.normalizeRequired(
                storyboardPromptText,
                "스토리보드 프롬프트는 필수입니다."
        );
        this.imageStylePromptText = TextValidator.normalizeRequired(
                imageStylePromptText,
                "이미지 스타일 프롬프트는 필수입니다."
        );
        this.imageAssetObjectKey = ImageObjectKeyPolicy.normalizeRequired(
                imageAssetObjectKey,
                "이미지 에셋 Object Key"
        );
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
}
