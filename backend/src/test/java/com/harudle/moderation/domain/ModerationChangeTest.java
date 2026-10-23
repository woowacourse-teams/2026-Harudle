package com.harudle.moderation.domain;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ModerationChangeTest {
    @Test
    void acceptsFiveHundredUnicodeCodePointsAndStripsOuterWhitespace() {
        String reason = "😀".repeat(500);
        assertThat(new ModerationChange(true, 0, "  " + reason + "  ").reason()).isEqualTo(reason);
        assertThatThrownBy(() -> new ModerationChange(true, 0, reason + "a"))
                .isInstanceOf(InvalidModerationChangeException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\u0000", "\uD800", "\uDC00", "a\uD800b"})
    void rejectsMissingOrUnrepresentableReason(String reason) {
        assertThatThrownBy(() -> new ModerationChange(true, 0, reason))
                .isInstanceOf(InvalidModerationChangeException.class);
    }

    @Test
    void rejectsNegativeAndOverflowingVersions() {
        assertThatThrownBy(() -> new ModerationChange(true, -1, "사유"))
                .isInstanceOf(InvalidModerationChangeException.class);
        assertThatThrownBy(() -> new ModerationChange(true, Long.MAX_VALUE, "사유"))
                .isInstanceOf(InvalidModerationChangeException.class);
    }
}
