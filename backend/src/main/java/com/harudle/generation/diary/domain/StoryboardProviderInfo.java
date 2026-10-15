package com.harudle.generation.diary.domain;

public record StoryboardProviderInfo(String provider, String model) {

    public StoryboardProviderInfo {
        if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
            throw new IllegalArgumentException("스토리보드 Provider와 모델은 필수입니다.");
        }
        provider = provider.strip();
        model = model.strip();
    }
}
