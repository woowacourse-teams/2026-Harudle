package com.harudle.legal.domain;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** contentHash는 배포한 UTF-8 JSON 리소스 원문 바이트의 SHA-256이다. */
public record LegalDocument(
        LegalDocumentType type,
        String version,
        String title,
        LocalDate effectiveDate,
        String contentHash,
        URI sourceUrl,
        List<LegalSection> sections
) {
    public LegalDocument {
        Objects.requireNonNull(type, "문서 종류는 필수입니다.");
        if (version == null || version.isBlank()) throw new IllegalArgumentException("문서 버전은 필수입니다.");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("문서 제목은 필수입니다.");
        Objects.requireNonNull(effectiveDate, "문서 시행일은 필수입니다.");
        if (contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("문서 해시는 SHA-256 소문자 16진수여야 합니다.");
        }
        Objects.requireNonNull(sourceUrl, "문서 출처는 필수입니다.");
        if (!"https".equals(sourceUrl.getScheme()) || sourceUrl.getHost() == null) {
            throw new IllegalArgumentException("문서 출처에는 HTTPS 주소가 필요합니다.");
        }
        sections = List.copyOf(Objects.requireNonNull(sections, "문서 본문은 필수입니다."));
        if (sections.isEmpty()) throw new IllegalArgumentException("문서 본문은 비어 있을 수 없습니다.");
    }
}
