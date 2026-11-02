package com.harudle.legal.presentation;

import com.harudle.legal.application.TermsAcceptanceCommand;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record AcceptTermsRequest(
        @NotNull @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}") String termsVersion,
        @NotNull @Pattern(regexp = "[0-9a-f]{64}") String termsContentHash,
        @NotNull @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}") String privacyNoticeVersion,
        @NotNull @Pattern(regexp = "[0-9a-f]{64}") String privacyNoticeContentHash,
        @NotNull @AssertTrue Boolean accepted, @NotNull @AssertTrue Boolean ageConfirmed) {
    TermsAcceptanceCommand toCommand() {
        return new TermsAcceptanceCommand(termsVersion, termsContentHash,
                privacyNoticeVersion, privacyNoticeContentHash, Boolean.TRUE.equals(accepted), Boolean.TRUE.equals(ageConfirmed));
    }
}
