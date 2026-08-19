package com.harudle.guest.infrastructure.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GuestSessionTokenHasherTest {

    private final GuestSessionTokenHasher tokenHasher = new GuestSessionTokenHasher();

    @Test
    @DisplayName("같은 게스트 세션 토큰은 항상 같은 해시를 만든다")
    void createsSameHashForSameToken() {
        String firstHash = tokenHasher.hash("guest-session-token");
        String secondHash = tokenHasher.hash("guest-session-token");

        assertThat(firstHash).isEqualTo(secondHash);
    }

    @Test
    @DisplayName("게스트 세션 토큰을 64자리 소문자 SHA-256 해시로 변환한다")
    void createsSha256Hash() {
        String tokenHash = tokenHasher.hash("guest-session-token");

        assertThat(tokenHash).hasSize(64);
        assertThat(tokenHash).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("서로 다른 게스트 세션 토큰은 서로 다른 해시를 만든다")
    void createsDifferentHashesForDifferentTokens() {
        String firstHash = tokenHasher.hash("first-guest-session-token");
        String secondHash = tokenHasher.hash("second-guest-session-token");

        assertThat(firstHash).isNotEqualTo(secondHash);
    }

    @Test
    @DisplayName("게스트 세션 토큰이 없으면 해시할 수 없다")
    void rejectsMissingRawToken() {
        assertThatThrownBy(() -> tokenHasher.hash(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("rawToken은 필수입니다.");

        assertThatThrownBy(() -> tokenHasher.hash("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rawToken은 비어 있을 수 없습니다.");
    }
}
