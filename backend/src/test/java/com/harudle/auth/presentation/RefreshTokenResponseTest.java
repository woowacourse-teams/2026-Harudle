package com.harudle.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.auth.application.IssuedAccessToken;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefreshTokenResponseTest {

    private static final Instant REFRESHED_AT = Instant.parse("2026-08-12T10:00:00Z");

    @Test
    @DisplayName("Access Token 발급 결과를 Bearer 응답으로 변환한다")
    void createsBearerResponse() {
        IssuedAccessToken issuedAccessToken = new IssuedAccessToken(
                "access-token",
                REFRESHED_AT.plusSeconds(1_800)
        );

        RefreshTokenResponse response = RefreshTokenResponse.from(
                issuedAccessToken,
                REFRESHED_AT
        );

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(1_800);
    }

    @Test
    @DisplayName("Access Token 만료 시간이 현재와 같으면 응답을 만들 수 없다")
    void rejectsExpiredAccessToken() {
        IssuedAccessToken issuedAccessToken = new IssuedAccessToken(
                "access-token",
                REFRESHED_AT
        );

        assertThatThrownBy(() -> RefreshTokenResponse.from(issuedAccessToken, REFRESHED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Bearer가 아닌 Token Type은 응답으로 만들 수 없다")
    void rejectsUnsupportedTokenType() {
        assertThatThrownBy(() -> new RefreshTokenResponse("access-token", "Basic", 1_800))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
