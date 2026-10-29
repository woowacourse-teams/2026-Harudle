package com.harudle.legal.domain;

import java.util.List;
import java.util.Objects;

/** 약관 본문은 HTML이 아닌 제목과 일반 문장으로 제공한다. */
public record LegalSection(String title, List<String> paragraphs) {
    public LegalSection {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("문서 절 제목은 필수입니다.");
        paragraphs = List.copyOf(Objects.requireNonNull(paragraphs, "문단은 필수입니다."));
        if (paragraphs.isEmpty() || paragraphs.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("문서 절에는 비어 있지 않은 문단이 필요합니다.");
        }
    }
}
