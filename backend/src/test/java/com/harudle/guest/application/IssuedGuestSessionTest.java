package com.harudle.guest.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IssuedGuestSessionTest {

    private static final Instant EXPIRES_AT = Instant.parse("2026-09-18T00:00:00Z");

    @Test
    @DisplayName("발급한 게스트 세션의 원문 토큰과 만료 시각을 보관한다")
    void keepsIssuedGuestSession() {
        IssuedGuestSession issuedSession = new IssuedGuestSession(
                "raw-guest-token",
                EXPIRES_AT
        );

        assertThat(issuedSession.rawToken()).isEqualTo("raw-guest-token");
        assertThat(issuedSession.expiresAt()).isEqualTo(EXPIRES_AT);
    }

    @Test
    @DisplayName("원문 토큰이 없으면 발급 결과를 생성할 수 없다")
    void rejectsMissingRawToken() {
        assertThatThrownBy(() -> new IssuedGuestSession(null, EXPIRES_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rawToken은 필수입니다.");

        assertThatThrownBy(() -> new IssuedGuestSession("   ", EXPIRES_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rawToken은 필수입니다.");
    }

    @Test
    @DisplayName("만료 시각이 없으면 발급 결과를 생성할 수 없다")
    void rejectsMissingExpiresAt() {
        assertThatThrownBy(() -> new IssuedGuestSession("raw-guest-token", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("expiresAt은 필수입니다.");
    }
}
