package com.harudle.legal.application;

import com.harudle.legal.domain.LegalDocument;

/** 수락 이력과 대조할 불변 약관의 버전·내용 식별값이다. */
public record TermsDocumentReference(String version, String contentHash) {
    public static TermsDocumentReference from(LegalDocument document) {
        return new TermsDocumentReference(document.version(), document.contentHash());
    }

    public boolean matches(TermsAcceptance acceptance) {
        return version.equals(acceptance.termsVersion()) && contentHash.equals(acceptance.termsContentHash());
    }
}
