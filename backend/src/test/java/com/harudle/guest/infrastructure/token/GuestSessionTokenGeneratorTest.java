package com.harudle.guest.infrastructure.token;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GuestSessionTokenGeneratorTest {

    private final GuestSessionTokenGenerator tokenGenerator = new GuestSessionTokenGenerator();

    @Test
    @DisplayName("256비트 URL-safe 게스트 세션 토큰을 생성한다")
    void generatesGuestSessionToken() {
        String rawToken = tokenGenerator.generate();

        assertThat(rawToken).hasSize(43);
        assertThat(rawToken).matches("[A-Za-z0-9_-]{43}");
        assertThat(rawToken).doesNotContain("=");
    }

    @Test
    @DisplayName("생성할 때마다 서로 다른 게스트 세션 토큰을 만든다")
    void generatesDifferentGuestSessionTokens() {
        String firstToken = tokenGenerator.generate();
        String secondToken = tokenGenerator.generate();

        assertThat(firstToken).isNotEqualTo(secondToken);
    }
}
