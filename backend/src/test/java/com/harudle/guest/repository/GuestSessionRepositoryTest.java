package com.harudle.guest.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.guest.domain.GuestSession;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Transactional
class GuestSessionRepositoryTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18-alpine");
    private static final String TOKEN_HASH = "a".repeat(64);
    private static final String ANOTHER_TOKEN_HASH = "b".repeat(64);
    private static final Instant CREATED_AT = Instant.parse("2026-08-19T00:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-19T00:00:00Z");

    @Container
    @ServiceConnection
    private static final PostgreSQLContainer POSTGRESQL_CONTAINER = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private GuestSessionRepository guestSessionRepository;

    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("게스트 세션을 저장하고 토큰 해시로 조회한다")
    void savesAndFindsByTokenHash() {
        User guestUser = saveGuestUser("게스트1");

        GuestSession session = GuestSession.create(
                guestUser.getId(),
                TOKEN_HASH,
                EXPIRES_AT,
                CREATED_AT
        );

        GuestSession savedSession =
                guestSessionRepository.saveAndFlush(session);

        UUID savedSessionId = savedSession.getId();

        entityManager.clear();

        GuestSession foundSession = guestSessionRepository
                .findByTokenHash(TOKEN_HASH)
                .orElseThrow();

        assertThat(foundSession.getId()).isEqualTo(savedSessionId);
        assertThat(foundSession.getGuestUserId()).isEqualTo(guestUser.getId());
        assertThat(foundSession.getTokenHash()).isEqualTo(TOKEN_HASH);
        assertThat(foundSession.getDiaryId()).isNull();
        assertThat(foundSession.getUsedAt()).isNull();
        assertThat(foundSession.getExpiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(foundSession.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("존재하지 않는 토큰 해시는 조회되지 않는다")
    void returnsEmptyWhenTokenHashDoesNotExist() {
        assertThat(
                guestSessionRepository.findByTokenHash(TOKEN_HASH)
        ).isEmpty();
    }

    @Test
    @DisplayName("같은 토큰 해시를 중복 저장할 수 없다")
    void rejectsDuplicateTokenHash() {
        User firstGuestUser = saveGuestUser("게스트1");
        User secondGuestUser = saveGuestUser("게스트2");

        guestSessionRepository.saveAndFlush(
                GuestSession.create(
                        firstGuestUser.getId(),
                        TOKEN_HASH,
                        EXPIRES_AT,
                        CREATED_AT
                )
        );

        GuestSession duplicatedSession = GuestSession.create(
                secondGuestUser.getId(),
                TOKEN_HASH,
                EXPIRES_AT,
                CREATED_AT
        );

        assertThatThrownBy(() ->
                guestSessionRepository.saveAndFlush(duplicatedSession)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("하나의 게스트 사용자는 하나의 세션만 가질 수 있다")
    void rejectsDuplicateGuestUser() {
        User guestUser = saveGuestUser("게스트1");

        guestSessionRepository.saveAndFlush(
                GuestSession.create(
                        guestUser.getId(),
                        TOKEN_HASH,
                        EXPIRES_AT,
                        CREATED_AT
                )
        );

        GuestSession duplicatedSession = GuestSession.create(
                guestUser.getId(),
                ANOTHER_TOKEN_HASH,
                EXPIRES_AT,
                CREATED_AT
        );

        assertThatThrownBy(() ->
                guestSessionRepository.saveAndFlush(duplicatedSession)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("존재하지 않는 게스트 사용자로 세션을 저장할 수 없다")
    void rejectsMissingGuestUser() {
        GuestSession session = GuestSession.create(
                UUID.randomUUID(),
                TOKEN_HASH,
                EXPIRES_AT,
                CREATED_AT
        );

        assertThatThrownBy(() ->
                guestSessionRepository.saveAndFlush(session)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    private User saveGuestUser(String name) {
        return userRepository.saveAndFlush(
                new User(
                        null,
                        name,
                        CREATED_AT
                )
        );
    }
}
