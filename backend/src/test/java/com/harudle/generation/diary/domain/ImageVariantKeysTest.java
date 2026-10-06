package com.harudle.generation.diary.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ImageVariantKeysTest {

    private static final String GENERATION_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String IMAGE_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String FOLDER = "harudle/generated/diary-images/dev/" + GENERATION_ID + "/" + IMAGE_ID + "/";

    @ParameterizedTest
    @ValueSource(strings = {"image-960.webp", "image-240.webp"})
    void displayVariantsUseSameOriginalCandidates(String filename) {
        assertThat(ImageVariantKeys.originalImageKeyCandidatesForLookup(FOLDER + filename))
                .containsExactly(FOLDER + "image.png", FOLDER + "image.jpg", FOLDER + "image.webp");
    }

    @ParameterizedTest
    @ValueSource(strings = {"image.png", "image.jpg", "image.webp"})
    void originalLookupPreservesKey(String filename) {
        assertThat(ImageVariantKeys.originalImageKeyCandidatesForLookup(FOLDER + filename)).containsExactly(FOLDER + filename);
    }

    @ParameterizedTest
    @ValueSource(strings = {"image.png", "image.jpg", "image.webp"})
    @DisplayName("원본 이미지 키는 백업 후보로 그대로 반환한다")
    void keepOriginalKey(String filename) {
        String originalKey = FOLDER + filename;

        assertThat(ImageVariantKeys.originalImageKeyCandidatesForBackup(originalKey)).containsExactly(originalKey);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "harudle/generated/diary-images/dev/550e8400-e29b-41d4-a716-446655440000/",
            "harudle/generated/diary-images/prod/550e8400-e29b-41d4-a716-446655440000/",
            "harudle/generated/diary-images/dev/550e8400-e29b-41d4-a716-446655440000/123e4567-e89b-12d3-a456-426614174000/",
            "harudle/generated/diary-images/prod/550e8400-e29b-41d4-a716-446655440000/123e4567-e89b-12d3-a456-426614174000/"
    })
    @DisplayName("상세 이미지 키의 환경과 기존 폴더 구조를 유지해 원본 후보를 만든다")
    void preserveFolderWhenConvertingDetailKey(String folder) {
        assertThat(ImageVariantKeys.originalImageKeyCandidatesForBackup(folder + "image-960.webp"))
                .containsExactly(folder + "image.png", folder + "image.jpg", folder + "image.webp");
    }

    @ParameterizedTest
    @ValueSource(strings = {"image.png", "image.jpg", "image.webp"})
    @DisplayName("UUID 폴더가 하나인 기존 원본 키도 변경하지 않는다")
    void keepLegacyOriginalKey(String filename) {
        String originalKey = "harudle/generated/diary-images/prod/" + GENERATION_ID + "/" + filename;

        assertThat(ImageVariantKeys.originalImageKeyCandidatesForBackup(originalKey)).containsExactly(originalKey);
    }

    @Test
    @DisplayName("폴더명에 상세 이미지 파일명이 포함되어도 마지막 파일명만 변환한다")
    void changeOnlyFinalFilename() {
        String folder = FOLDER + "image-960.webp/";

        assertThat(ImageVariantKeys.originalImageKeyCandidatesForBackup(folder + "image-960.webp"))
                .containsExactly(folder + "image.png", folder + "image.jpg", folder + "image.webp");
    }

    @ParameterizedTest
    @ValueSource(strings = {"image-240.webp", "image.gif", "image.jpeg", "reference.png", "image-960.webp.bak"})
    @DisplayName("썸네일과 지원하지 않는 파일명은 백업용 변환에서 거절한다")
    void rejectUnsupportedFilename(String filename) {
        assertThatThrownBy(() -> ImageVariantKeys.originalImageKeyCandidatesForBackup(FOLDER + filename))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    @DisplayName("백업할 이미지 키가 없으면 거절한다")
    void rejectMissingKey(String key) {
        assertThatThrownBy(() -> ImageVariantKeys.originalImageKeyCandidatesForBackup(key))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
