package com.harudle.legal.application;

public record TermsAcceptanceCommand(String termsVersion, String termsContentHash,
        String privacyNoticeVersion, String privacyNoticeContentHash, boolean accepted, boolean ageConfirmed) {
}
