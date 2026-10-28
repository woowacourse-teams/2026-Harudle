package com.harudle.legal.application;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** terms·privacyNotice는 현재 열람 문서다. acceptedAt은 접근에 유효한 수락의 버전별 최초 시각이며 현재 버전을 우선한다. */
public record TermsAcceptanceStatus(boolean enforcementEnabled, boolean requiresAcceptance,
        DocumentReference terms, DocumentReference privacyNotice, @Nullable Instant acceptedAt, int minimumAge) {
    public record DocumentReference(String version, String contentHash) {
    }
}
