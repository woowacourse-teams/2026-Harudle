package com.harudle.legal.application;

import java.time.Instant;

public record TermsAcceptance(String termsVersion, String termsContentHash,
        String privacyNoticeVersion, String privacyNoticeContentHash, Instant acceptedAt) {
}
