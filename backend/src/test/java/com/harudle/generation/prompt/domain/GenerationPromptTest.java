package com.harudle.generation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GenerationPromptTest {

    @Test
    @DisplayName("생성 프롬프트를 생성하고 문자열 앞뒤 공백을 제거한다")
    void createGenerationPrompt() {
        GenerationPrompt prompt = new GenerationPrompt(
                " 스토리보드 프롬프트 ",
                " 이미지 스타일 프롬프트 ",
                " references/style.png "
        );

        assertThat(prompt.getId()).isNull();
        assertThat(prompt.getStoryboardPromptText()).isEqualTo("스토리보드 프롬프트");
        assertThat(prompt.getImageStylePromptText()).isEqualTo("이미지 스타일 프롬프트");
        assertThat(prompt.getImageAssetObjectKey()).isEqualTo("references/style.png");
    }

    @Test
    @DisplayName("스토리보드 프롬프트가 비어 있으면 생성할 수 없다")
    void rejectBlankStoryboardPrompt() {
        assertThatThrownBy(() -> new GenerationPrompt(
                " ",
                "이미지 스타일 프롬프트",
                "references/style.png"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("스토리보드 프롬프트");
    }

    @Test
    @DisplayName("이미지 스타일 프롬프트가 비어 있으면 생성할 수 없다")
    void rejectBlankImageStylePrompt() {
        assertThatThrownBy(() -> new GenerationPrompt(
                "스토리보드 프롬프트",
                " ",
                "references/style.png"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이미지 스타일 프롬프트");
    }

    @Test
    @DisplayName("이미지 에셋 Object Key가 비어 있으면 생성할 수 없다")
    void rejectBlankImageAssetObjectKey() {
        assertThatThrownBy(() -> new GenerationPrompt(
                "스토리보드 프롬프트",
                "이미지 스타일 프롬프트",
                " "
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Object Key");
    }

    @Test
    @DisplayName("이미지 에셋 Object Key가 UTF-8 기준 1,024바이트를 초과하면 생성할 수 없다")
    void rejectLongImageAssetObjectKey() {
        assertThatThrownBy(() -> new GenerationPrompt(
                "스토리보드 프롬프트",
                "이미지 스타일 프롬프트",
                "가".repeat(342)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1,024바이트");
    }
}
