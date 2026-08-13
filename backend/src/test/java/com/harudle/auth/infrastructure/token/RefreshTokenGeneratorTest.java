package com.harudle.auth.infrastructure.token;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefreshTokenGeneratorTest {

    private final RefreshTokenGenerator refreshTokenGenerator = new RefreshTokenGenerator();

    @Test
    @DisplayName("암호학적으로 안전한 형식의 Refresh Token을 생성한다")
    void generatesRefreshToken() {
        String rawToken = refreshTokenGenerator.generate();

        assertThat(rawToken).hasSize(43);
        assertThat(rawToken).doesNotContain("=");
    }

    @Test
    @DisplayName("생성할 때마다 서로 다른 Refresh Token을 만든다")
    void generatesDifferentTokens() {
        String firstToken = refreshTokenGenerator.generate();
        String secondToken = refreshTokenGenerator.generate();

        assertThat(firstToken).isNotEqualTo(secondToken);
    }
}
