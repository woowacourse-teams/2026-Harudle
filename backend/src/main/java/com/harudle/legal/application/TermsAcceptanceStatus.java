package com.harudle.legal.application;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record TermsAcceptanceStatus(boolean enforcementEnabled, boolean requiresAcceptance,
        DocumentReference terms, DocumentReference privacyNotice, @Nullable Instant acceptedAt, int minimumAge) {
    public record DocumentReference(String version, String contentHash) {
    }
}
