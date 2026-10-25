package com.harudle.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.TestcontainersConfiguration;
import com.harudle.auth.application.port.AccountRestrictionReader;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableConfigurationProperties(AuthProperties.class)
@Import({
        TestcontainersConfiguration.class,
        RefreshTokenService.class,
        AccountAccessPolicy.class,
        RefreshTokenGenerator.class,
        RefreshTokenHasher.class
})
class RefreshTokenServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-12T10:00:00Z");
    private static final Instant ROTATED_AT = Instant.parse("2026-08-12T11:00:00Z");
    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(14);

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RefreshTokenHasher refreshTokenHasher;

    @MockitoBean
    private AccountRestrictionReader restrictions;

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
        assertThat(savedToken.getSessionId()).isNotNull();
        assertThat(sessionCount(user)).isEqualTo(1);
    }

    @Test
    @DisplayName("Refresh Token을 Rotation하면 기존 토큰을 폐기하고 새 토큰을 발급한다")
    void rotatesRefreshToken() {
        User user = saveUser();
        IssuedRefreshToken firstToken = refreshTokenService.issue(user, CREATED_AT);
        flushAndClear();

        RotatedRefreshToken rotatedToken = refreshTokenService.rotate(
                firstToken.rawToken(),
                ROTATED_AT
        );
        flushAndClear();

        RefreshToken revokedToken = findToken(firstToken.rawToken());
        IssuedRefreshToken secondToken = rotatedToken.issuedRefreshToken();
        RefreshToken newToken = findToken(secondToken.rawToken());

        assertThat(secondToken.rawToken()).isNotEqualTo(firstToken.rawToken());
        assertThat(rotatedToken.userId()).isEqualTo(user.getId());
        assertThat(rotatedToken.sessionId()).isEqualTo(revokedToken.getSessionId());
        assertThat(revokedToken.isRevoked()).isTrue();
        assertThat(revokedToken.getRevokedAt()).isEqualTo(ROTATED_AT);
        assertThat(newToken.isRevoked()).isFalse();
        assertThat(newToken.getExpiresAt()).isEqualTo(ROTATED_AT.plus(REFRESH_TOKEN_TTL));
        assertThat(newToken.getUser().getId()).isEqualTo(user.getId());
        assertThat(newToken.getSessionId()).isEqualTo(revokedToken.getSessionId());
        assertThat(sessionCount(user)).isEqualTo(1);
        assertThat(refreshTokenRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 사용자가 다시 로그인하면 서로 다른 세션을 만든다")
    void createsSeparateSessionsForLogins() {
        User user = saveUser();

        IssuedRefreshToken first = refreshTokenService.issue(user, CREATED_AT);
        IssuedRefreshToken second = refreshTokenService.issue(user, ROTATED_AT);
        flushAndClear();

        assertThat(findToken(first.rawToken()).getSessionId())
                .isNotEqualTo(findToken(second.rawToken()).getSessionId());
        assertThat(sessionCount(user)).isEqualTo(2);
    }

    @Test
    @DisplayName("세션 ID가 없는 기존 토큰을 갱신하면 새 세션에 연결한다")
    void connectsLegacyTokenToNewSessionOnRotation() {
        User user = saveUser();
        RefreshToken legacy = refreshTokenRepository.save(new RefreshToken(
                user, refreshTokenHasher.hash("legacy"), CREATED_AT.plus(REFRESH_TOKEN_TTL), CREATED_AT));
        flushAndClear();

        RotatedRefreshToken rotated = refreshTokenService.rotate("legacy", ROTATED_AT);
        flushAndClear();

        assertThat(legacy.getSessionId()).isNull();
        assertThat(rotated.sessionId()).isNotNull();
        assertThat(findToken(rotated.issuedRefreshToken().rawToken()).getSessionId()).isNotNull();
        assertThat(sessionCount(user)).isEqualTo(1);
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
    @DisplayName("세션 ID가 없는 기존 토큰은 로그아웃할 때 토큰만 폐기한다")
    void revokesLegacyTokenWithoutSession() {
        User user = saveUser();
        refreshTokenRepository.save(new RefreshToken(user, refreshTokenHasher.hash("legacy-logout"),
                CREATED_AT.plus(REFRESH_TOKEN_TTL), CREATED_AT));
        flushAndClear();

        refreshTokenService.revoke("legacy-logout", ROTATED_AT);
        flushAndClear();

        assertThat(findToken("legacy-logout").isRevoked()).isTrue();
        assertThat(sessionCount(user)).isZero();
    }

    @Test
    @DisplayName("로그아웃하면 해당 세션의 토큰과 푸시 등록을 해제하고 미발송 작업을 취소한다")
    void revokesOnlyLoggedOutSession() {
        User user = saveUser();
        IssuedRefreshToken first = refreshTokenService.issue(user, CREATED_AT);
        RotatedRefreshToken rotated = refreshTokenService.rotate(first.rawToken(), ROTATED_AT);
        IssuedRefreshToken other = refreshTokenService.issue(user, CREATED_AT);
        flushAndClear();
        UUID firstSession = rotated.sessionId();
        UUID otherSession = findToken(other.rawToken()).getSessionId();
        UUID firstRegistration = registration(user, firstSession, "first-fid");
        UUID otherRegistration = registration(user, otherSession, "other-fid");
        UUID feedId = feed(user);
        job(feedId, user, firstRegistration, "PENDING");
        job(feedId, user, firstRegistration, "PROCESSING");
        job(feedId, user, firstRegistration, "SENT");
        job(feedId, user, otherRegistration, "PENDING");

        refreshTokenService.revoke(first.rawToken(), ROTATED_AT.plusSeconds(1));
        refreshTokenService.revoke(first.rawToken(), ROTATED_AT.plusSeconds(2));
        flushAndClear();

        assertThat(number("SELECT count(*) FROM auth_sessions WHERE id = ? AND revoked_at IS NOT NULL", firstSession))
                .isEqualTo(1);
        assertThat(number("SELECT count(*) FROM refresh_tokens WHERE session_id = ? AND revoked_at IS NULL", firstSession))
                .isZero();
        assertThat(number("SELECT count(*) FROM push_registrations WHERE id = ? AND revoked_at IS NOT NULL",
                firstRegistration)).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM notification_outbox WHERE push_registration_id = ? "
                + "AND status = 'CANCELLED' AND lock_token IS NULL AND locked_until IS NULL",
                firstRegistration)).isEqualTo(2);
        assertThat(number("SELECT count(*) FROM notification_outbox WHERE push_registration_id = ? "
                + "AND status = 'SENT'", firstRegistration)).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM auth_sessions WHERE id = ? AND revoked_at IS NULL", otherSession))
                .isEqualTo(1);
        assertThat(number("SELECT count(*) FROM push_registrations WHERE id = ? AND revoked_at IS NULL",
                otherRegistration)).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM notification_outbox WHERE push_registration_id = ? "
                + "AND status = 'PENDING'", otherRegistration)).isEqualTo(1);
        assertThatThrownBy(() -> refreshTokenService.rotate(rotated.issuedRefreshToken().rawToken(),
                ROTATED_AT.plusSeconds(3))).isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("토큰 갱신과 로그아웃이 동시에 진행돼도 로그아웃 후 유효한 토큰이 남지 않는다")
    void logoutWinsConcurrentRotation() throws Exception {
        User user = userRepository.save(new User("race-" + UUID.randomUUID() + "@example.com", "하루들", CREATED_AT));
        IssuedRefreshToken oldToken = refreshTokenService.issue(user, CREATED_AT);
        IssuedRefreshToken currentToken = refreshTokenService.rotate(
                oldToken.rawToken(), CREATED_AT.plusSeconds(1)).issuedRefreshToken();
        UUID sessionId = jdbcTemplate.queryForObject(
                "SELECT session_id FROM refresh_tokens WHERE token_hash = ?", UUID.class,
                refreshTokenHasher.hash(currentToken.rawToken()));
        CountDownLatch start = new CountDownLatch(1);

        try (var workers = Executors.newFixedThreadPool(2)) {
            var rotation = workers.submit(() -> {
                start.await();
                try {
                    refreshTokenService.rotate(currentToken.rawToken(), ROTATED_AT);
                } catch (InvalidRefreshTokenException ignored) {
                    // 로그아웃이 먼저 끝난 경우다.
                }
                return null;
            });
            var logout = workers.submit(() -> {
                start.await();
                refreshTokenService.revoke(oldToken.rawToken(), ROTATED_AT.plusSeconds(1));
                return null;
            });
            start.countDown();
            rotation.get(10, TimeUnit.SECONDS);
            logout.get(10, TimeUnit.SECONDS);
        }

        assertThat(number("SELECT count(*) FROM refresh_tokens WHERE session_id = ? AND revoked_at IS NULL",
                sessionId)).isZero();
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", user.getId());
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

    private UUID registration(User user, UUID sessionId, String fid) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO push_registrations (id, user_id, session_id, device_id, recipient_value)
                VALUES (?, ?, ?, ?, ?)
                """, id, user.getId(), sessionId, UUID.randomUUID(), fid);
        return id;
    }

    private UUID feed(User user) {
        UUID diaryId = UUID.randomUUID();
        UUID feedId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO diaries (id, user_id, diary_date, source_text) "
                + "VALUES (?, ?, CURRENT_DATE, '일기')", diaryId, user.getId());
        jdbcTemplate.update("INSERT INTO feeds (id, diary_id, category_id) "
                + "VALUES (?, ?, (SELECT id FROM categories WHERE name = '일상'))", feedId, diaryId);
        return feedId;
    }

    private void job(UUID feedId, User user, UUID registrationId, String status) {
        jdbcTemplate.update("""
                INSERT INTO notification_outbox
                    (id, event_id, feed_id, recipient_user_id, push_registration_id, status,
                     lock_token, locked_until, sent_at)
                VALUES (?, ?, ?, ?, ?, ?, CASE WHEN ? = 'PROCESSING' THEN gen_random_uuid() END,
                        CASE WHEN ? = 'PROCESSING' THEN CURRENT_TIMESTAMP + INTERVAL '1 minute' END,
                        CASE WHEN ? = 'SENT' THEN CURRENT_TIMESTAMP END)
                """, UUID.randomUUID(), UUID.randomUUID(), feedId, user.getId(), registrationId,
                status, status, status, status);
    }

    private long number(String sql, UUID value) {
        return jdbcTemplate.queryForObject(sql, Long.class, value);
    }

    private long sessionCount(User user) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM auth_sessions WHERE user_id = ?", Long.class, user.getId());
    }
}
