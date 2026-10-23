package com.harudle.generation.diary.domain;

public record GenerationTokenUsage(
        Integer promptTokenCount,
        Integer candidateTokenCount,
        Integer thoughtTokenCount,
        Integer totalTokenCount
) {
}
