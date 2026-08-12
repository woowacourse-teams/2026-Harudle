package com.harudle.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.auth.domain.RefreshToken;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.RefreshTokenRepository;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.infrastructure.token.RefreshTokenGenerator;
import com.harudle.auth.infrastructure.token.RefreshTokenHasher;
import com.harudle.common.security.AuthProperties;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableConfigurationProperties(AuthProperties.class)
@Import({
        RefreshTokenService.class,
        RefreshTokenGenerator.class,
        RefreshTokenHasher.class
})
class RefreshTokenServiceTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18-alpine");
    private static final Instant CREATED_AT = Instant.parse("2026-08-12T10:00:00Z");
    private static final Instant ROTATED_AT = Instant.parse("2026-08-12T11:00:00Z");
    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(14);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL_CONTAINER = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RefreshTokenHasher refreshTokenHasher;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Refresh Token을 발급하고 해시만 저장한다")
    void issuesRefreshTokenAndStoresOnlyHash() {
        User user = saveUser();

        IssuedRefreshToken issuedToken = refreshTokenService.issue(user, CREATED_AT);
        flushAndClear();

        RefreshToken savedToken = findToken(issuedToken.rawToken());

        assertThat(issuedToken.rawToken()).isNotEqualTo(savedToken.getTokenHash());
        assertThat(savedToken.getTokenHash()).isEqualTo(refreshTokenHasher.hash(issuedToken.rawToken()));
        assertThat(savedToken.getExpiresAt()).isEqualTo(CREATED_AT.plus(REFRESH_TOKEN_TTL));
        assertThat(savedToken.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(savedToken.getUser().getId()).isEqualTo(user.getId());
    }

    @Test
    @DisplayName("Refresh Token을 Rotation하면 기존 토큰을 폐기하고 새 토큰을 발급한다")
    void rotatesRefreshToken() {
        User user = saveUser();
        IssuedRefreshToken firstToken = refreshTokenService.issue(user, CREATED_AT);
        flushAndClear();

        IssuedRefreshToken secondToken = refreshTokenService.rotate(
                firstToken.rawToken(),
                ROTATED_AT
        );
        flushAndClear();

        RefreshToken revokedToken = findToken(firstToken.rawToken());
        RefreshToken newToken = findToken(secondToken.rawToken());

        assertThat(secondToken.rawToken()).isNotEqualTo(firstToken.rawToken());
        assertThat(revokedToken.isRevoked()).isTrue();
        assertThat(revokedToken.getRevokedAt()).isEqualTo(ROTATED_AT);
        assertThat(newToken.isRevoked()).isFalse();
        assertThat(newToken.getExpiresAt()).isEqualTo(ROTATED_AT.plus(REFRESH_TOKEN_TTL));
        assertThat(newToken.getUser().getId()).isEqualTo(user.getId());
        assertThat(refreshTokenRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("폐기된 Refresh Token은 다시 Rotation할 수 없다")
    void rejectsRevokedToken() {
        User user = saveUser();
        IssuedRefreshToken issuedToken = refreshTokenService.issue(user, CREATED_AT);
        refreshTokenService.revoke(issuedToken.rawToken(), ROTATED_AT);

        assertThatThrownBy(() -> refreshTokenService.rotate(issuedToken.rawToken(), ROTATED_AT))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @DisplayName("만료된 Refresh Token은 Rotation할 수 없다")
    void rejectsExpiredToken() {
        User user = saveUser();
        IssuedRefreshToken issuedToken = refreshTokenService.issue(user, CREATED_AT);
        Instant expirationTime = CREATED_AT.plus(REFRESH_TOKEN_TTL);

        assertThatThrownBy(() -> refreshTokenService.rotate(issuedToken.rawToken(), expirationTime))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @DisplayName("존재하지 않는 Refresh Token은 Rotation할 수 없다")
    void rejectsUnknownToken() {
        assertThatThrownBy(() -> refreshTokenService.rotate("unknown-token", ROTATED_AT))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @DisplayName("로그아웃에 사용할 Refresh Token이 없어도 폐기 요청은 실패하지 않는다")
    void ignoresMissingTokenWhenRevoking() {
        refreshTokenService.revoke(null, ROTATED_AT);
        refreshTokenService.revoke("unknown-token", ROTATED_AT);

        assertThat(refreshTokenRepository.count()).isZero();
    }

    @Test
    @DisplayName("탈퇴한 사용자의 Refresh Token은 발급할 수 없다")
    void rejectsDeletedUser() {
        User user = saveUser();
        flushAndClear();
        markUserAsDeleted(user);
        entityManager.clear();

        User deletedUser = userRepository.findById(user.getId()).orElseThrow();

        assertThatThrownBy(() -> refreshTokenService.issue(deletedUser, CREATED_AT))
                .isInstanceOf(InactiveUserException.class);
    }

    private User saveUser() {
        return userRepository.save(
                new User(
                        "user@example.com",
                        "하루들",
                        CREATED_AT
                )
        );
    }

    private RefreshToken findToken(String rawToken) {
        String tokenHash = refreshTokenHasher.hash(rawToken);

        return refreshTokenRepository.findByTokenHash(tokenHash).orElseThrow();
    }

    private void markUserAsDeleted(User user) {
        jdbcTemplate.update(
                "UPDATE users SET deleted_at = ? WHERE id = ?",
                Timestamp.from(ROTATED_AT),
                user.getId()
        );
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
