package com.harudle.guest.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.guest.configuration.GuestSessionProperties;
import com.harudle.guest.domain.GuestSession;
import com.harudle.guest.infrastructure.token.GuestSessionTokenGenerator;
import com.harudle.guest.infrastructure.token.GuestSessionTokenHasher;
import com.harudle.guest.repository.GuestSessionRepository;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(
        showSql = false,
        properties = "harudle.guest.session.ttl=30d"
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableConfigurationProperties(GuestSessionProperties.class)
@Import({
        GuestSessionService.class,
        GuestSessionTokenGenerator.class,
        GuestSessionTokenHasher.class
})
class GuestSessionServiceTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18-alpine");
    private static final Instant ISSUED_AT = Instant.parse("2026-08-19T00:00:00Z");
    private static final Duration SESSION_TTL = Duration.ofDays(30);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL_CONTAINER = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private GuestSessionService guestSessionService;

    @Autowired
    private GuestSessionRepository guestSessionRepository;

    @Autowired
    private GuestSessionTokenHasher tokenHasher;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("게스트 사용자와 세션을 함께 생성하고 토큰 해시만 저장한다")
    void issuesGuestSessionAndStoresOnlyTokenHash() {
        IssuedGuestSession issuedSession = guestSessionService.issueOrReuse(null, ISSUED_AT);

        flushAndClear();

        String tokenHash = tokenHasher.hash(issuedSession.rawToken());
        GuestSession savedSession = guestSessionRepository
                .findByTokenHash(tokenHash)
                .orElseThrow();
        User savedGuestUser = userRepository
                .findById(savedSession.getGuestUserId())
                .orElseThrow();

        assertThat(issuedSession.rawToken()).isNotEqualTo(savedSession.getTokenHash());
        assertThat(savedSession.getTokenHash()).isEqualTo(tokenHash);
        assertThat(savedSession.getExpiresAt()).isEqualTo(ISSUED_AT.plus(SESSION_TTL));
        assertThat(savedSession.getCreatedAt()).isEqualTo(ISSUED_AT);
        assertThat(savedSession.getUpdatedAt()).isEqualTo(ISSUED_AT);
        assertThat(savedSession.getUsedAt()).isNull();
        assertThat(savedSession.getDiaryId()).isNull();

        assertThat(savedGuestUser.getPrimaryEmail()).isNull();
        assertThat(savedGuestUser.getName()).isNotBlank();
        assertThat(savedGuestUser.getCreatedAt()).isEqualTo(ISSUED_AT);
        assertThat(issuedSession.expiresAt()).isEqualTo(ISSUED_AT.plus(SESSION_TTL));
        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(guestSessionRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("현재 토큰이 없으면 서로 다른 게스트 사용자와 세션을 생성한다")
    void issuesDifferentGuestSessionsWithoutCurrentToken() {
        IssuedGuestSession firstSession = guestSessionService.issueOrReuse(null, ISSUED_AT);
        IssuedGuestSession secondSession = guestSessionService.issueOrReuse(null, ISSUED_AT);
        flushAndClear();

        GuestSession firstSavedSession = findSession(firstSession.rawToken());
        GuestSession secondSavedSession = findSession(secondSession.rawToken());

        assertThat(firstSession.rawToken()).isNotEqualTo(secondSession.rawToken());
        assertThat(firstSavedSession.getId()).isNotEqualTo(secondSavedSession.getId());
        assertThat(firstSavedSession.getGuestUserId())
                .isNotEqualTo(secondSavedSession.getGuestUserId());
        assertThat(userRepository.count()).isEqualTo(2);
        assertThat(guestSessionRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("현재 토큰의 게스트 세션이 유효하면 기존 세션을 재사용한다")
    void reusesCurrentGuestSession() {
        IssuedGuestSession firstSession = guestSessionService.issueOrReuse(null, ISSUED_AT);

        flushAndClear();

        IssuedGuestSession reusedSession = guestSessionService.issueOrReuse(
                firstSession.rawToken(),
                ISSUED_AT.plusSeconds(1)
        );

        flushAndClear();

        assertThat(reusedSession.rawToken()).isEqualTo(firstSession.rawToken());
        assertThat(reusedSession.expiresAt()).isEqualTo(firstSession.expiresAt());
        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(guestSessionRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("현재 토큰의 게스트 세션이 만료되었으면 새 세션을 발급한다")
    void issuesNewGuestSessionWhenCurrentSessionExpired() {
        IssuedGuestSession expiredSession = guestSessionService.issueOrReuse(null, ISSUED_AT);

        flushAndClear();

        IssuedGuestSession newSession = guestSessionService.issueOrReuse(
                expiredSession.rawToken(),
                expiredSession.expiresAt()
        );

        flushAndClear();

        assertThat(newSession.rawToken()).isNotEqualTo(expiredSession.rawToken());
        assertThat(newSession.expiresAt())
                .isEqualTo(expiredSession.expiresAt().plus(SESSION_TTL));
        assertThat(userRepository.count()).isEqualTo(2);
        assertThat(guestSessionRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("현재 토큰과 일치하는 세션이 없으면 새 세션을 발급한다")
    void issuesNewGuestSessionWhenCurrentTokenIsUnknown() {
        IssuedGuestSession issuedSession = guestSessionService.issueOrReuse(
                "unknown-guest-session-token",
                ISSUED_AT
        );

        flushAndClear();

        assertThat(issuedSession.rawToken()).isNotEqualTo("unknown-guest-session-token");
        assertThat(findSession(issuedSession.rawToken())).isNotNull();
        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(guestSessionRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("발급 시각이 없으면 게스트 세션을 생성하지 않는다")
    void rejectsMissingIssuedAt() {
        assertThatThrownBy(() -> guestSessionService.issueOrReuse(null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("now는 필수입니다.");

        assertThat(userRepository.count()).isZero();
        assertThat(guestSessionRepository.count()).isZero();
    }

    private GuestSession findSession(String rawToken) {
        return guestSessionRepository
                .findByTokenHash(tokenHasher.hash(rawToken))
                .orElseThrow();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
