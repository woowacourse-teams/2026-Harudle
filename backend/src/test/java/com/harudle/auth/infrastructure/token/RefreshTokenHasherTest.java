package com.harudle.auth.infrastructure.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefreshTokenHasherTest {

    private final RefreshTokenHasher refreshTokenHasher = new RefreshTokenHasher();

    @Test
    @DisplayName("같은 Refresh Token은 항상 같은 해시를 만든다")
    void createsSameHashForSameToken() {
        String firstHash = refreshTokenHasher.hash("refresh-token");
        String secondHash = refreshTokenHasher.hash("refresh-token");

        assertThat(firstHash).isEqualTo(secondHash);
    }

    @Test
    @DisplayName("Refresh Token 해시는 64자리 소문자 16진수다")
    void createsSha256Hash() {
        String hash = refreshTokenHasher.hash("refresh-token");

        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("서로 다른 Refresh Token은 서로 다른 해시를 만든다")
    void createsDifferentHashesForDifferentTokens() {
        String firstHash = refreshTokenHasher.hash("first-token");
        String secondHash = refreshTokenHasher.hash("second-token");

        assertThat(firstHash).isNotEqualTo(secondHash);
    }

    @Test
    @DisplayName("빈 Refresh Token은 해시할 수 없다")
    void rejectsBlankToken() {
        assertThatThrownBy(() -> refreshTokenHasher.hash("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
