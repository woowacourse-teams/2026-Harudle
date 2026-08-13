package com.harudle.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefreshTokenTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-12T10:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-08-26T10:00:00Z");
    private static final Instant BEFORE_EXPIRATION = Instant.parse("2026-08-26T09:59:59Z");
    private static final Instant AT_EXPIRATION = EXPIRES_AT;
    private static final String TOKEN_HASH = "a".repeat(64);

    @Test
    @DisplayName("Refresh Token을 생성한다")
    void createsRefreshToken() {
        User user = createUser();

        RefreshToken refreshToken = new RefreshToken(
                user,
                TOKEN_HASH,
                EXPIRES_AT,
                CREATED_AT
        );

        assertThat(refreshToken.getUser()).isSameAs(user);
        assertThat(refreshToken.getTokenHash()).isEqualTo(TOKEN_HASH);
        assertThat(refreshToken.getExpiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(refreshToken.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(refreshToken.getRevokedAt()).isNull();
    }

    @Test
    @DisplayName("만료 전 Refresh Token은 사용할 수 있다")
    void canUseBeforeExpiration() {
        RefreshToken refreshToken = createRefreshToken();

        assertThat(refreshToken.isExpiredAt(BEFORE_EXPIRATION)).isFalse();
        assertThat(refreshToken.canUseAt(BEFORE_EXPIRATION)).isTrue();
    }

    @Test
    @DisplayName("만료 시각에 도달한 Refresh Token은 사용할 수 없다")
    void cannotUseAtExpiration() {
        RefreshToken refreshToken = createRefreshToken();

        assertThat(refreshToken.isExpiredAt(AT_EXPIRATION)).isTrue();
        assertThat(refreshToken.canUseAt(AT_EXPIRATION)).isFalse();
    }

    @Test
    @DisplayName("Refresh Token을 폐기하면 사용할 수 없다")
    void cannotUseRevokedToken() {
        RefreshToken refreshToken = createRefreshToken();

        refreshToken.revoke(BEFORE_EXPIRATION);

        assertThat(refreshToken.isRevoked()).isTrue();
        assertThat(refreshToken.getRevokedAt()).isEqualTo(BEFORE_EXPIRATION);
        assertThat(refreshToken.canUseAt(BEFORE_EXPIRATION)).isFalse();
    }

    @Test
    @DisplayName("이미 폐기된 Refresh Token은 폐기 시각을 바꾸지 않는다")
    void keepsFirstRevokedAt() {
        RefreshToken refreshToken = createRefreshToken();
        Instant firstRevokedAt = Instant.parse("2026-08-12T11:00:00Z");
        Instant secondRevokedAt = Instant.parse("2026-08-12T12:00:00Z");

        refreshToken.revoke(firstRevokedAt);
        refreshToken.revoke(secondRevokedAt);

        assertThat(refreshToken.getRevokedAt()).isEqualTo(firstRevokedAt);
    }

    @Test
    @DisplayName("만료 시각이 생성 시각보다 빠르면 Refresh Token을 생성할 수 없다")
    void rejectsInvalidExpiration() {
        assertThatThrownBy(() -> new RefreshToken(
                createUser(),
                TOKEN_HASH,
                CREATED_AT,
                CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private RefreshToken createRefreshToken() {
        return new RefreshToken(
                createUser(),
                TOKEN_HASH,
                EXPIRES_AT,
                CREATED_AT
        );
    }

    private User createUser() {
        return new User(
                "user@example.com",
                "하루들",
                CREATED_AT
        );
    }
}
