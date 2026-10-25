package com.harudle.generation.diary.service.port.dto;

import com.harudle.generation.diary.domain.GenerationTokenUsage;
import com.harudle.generation.diary.domain.Storyboard;
import com.harudle.generation.diary.domain.StoryboardProviderInfo;
import java.util.Objects;

public record GeneratedStoryboard(
        Storyboard storyboard,
        GenerationTokenUsage tokenUsage,
        StoryboardProviderInfo providerInfo
) {

    public GeneratedStoryboard(Storyboard storyboard, GenerationTokenUsage tokenUsage) {
        this(storyboard, tokenUsage, null);
    }

    public GeneratedStoryboard {
        Objects.requireNonNull(storyboard, "스토리보드 생성 결과는 필수입니다.");
    }
}
