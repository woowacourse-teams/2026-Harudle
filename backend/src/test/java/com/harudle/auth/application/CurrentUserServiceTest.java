package com.harudle.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.OAuthProvider;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.OAuthAccountRepository;
import com.harudle.auth.infrastructure.UserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
@Import(CurrentUserService.class)
class CurrentUserServiceTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18-alpine");
    private static final Instant CREATED_AT = Instant.parse("2026-08-11T10:00:00Z");
    private static final Instant DELETED_AT = Instant.parse("2026-08-12T09:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL_CONTAINER = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private CurrentUserService currentUserService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OAuthAccountRepository oauthAccountRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("현재 사용자의 프로필과 OAuth 제공자를 조회한다")
    void findsCurrentUser() {
        User user = saveUser("user@example.com", "하루들");
        saveOAuthAccount(user, "12345", "user@example.com");
        flushAndClear();

        CurrentUserResult result = currentUserService.find(user.getId());

        assertThat(result.id()).isEqualTo(user.getId());
        assertThat(result.name()).isEqualTo("하루들");
        assertThat(result.email()).isEqualTo("user@example.com");
        assertThat(result.oauthProvider()).isEqualTo(OAuthProvider.KAKAO);
        assertThat(result.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("사용자가 존재하지 않으면 현재 사용자를 조회할 수 없다")
    void rejectsMissingUser() {
        assertThatThrownBy(() -> currentUserService.find(UUID.randomUUID()))
                .isInstanceOf(InvalidCurrentUserException.class);
    }

    @Test
    @DisplayName("탈퇴한 사용자는 현재 사용자로 조회할 수 없다")
    void rejectsDeletedUser() {
        User user = saveUser("deleted@example.com", "탈퇴 사용자");
        saveOAuthAccount(user, "12345", "deleted@example.com");
        flushAndClear();
        markUserAsDeleted(user);
        entityManager.clear();

        assertThatThrownBy(() -> currentUserService.find(user.getId()))
                .isInstanceOf(InvalidCurrentUserException.class);
    }

    @Test
    @DisplayName("OAuth 계정이 없으면 현재 사용자를 조회할 수 없다")
    void rejectsUserWithoutOAuthAccount() {
        User user = saveUser("user@example.com", "하루들");
        flushAndClear();

        assertThatThrownBy(() -> currentUserService.find(user.getId()))
                .isInstanceOf(InvalidCurrentUserException.class);
    }

    @Test
    @DisplayName("사용자 식별자가 없으면 현재 사용자를 조회할 수 없다")
    void rejectsNullUserId() {
        assertThatThrownBy(() -> currentUserService.find(null))
                .isInstanceOf(NullPointerException.class);
    }

    private User saveUser(String email, String name) {
        return userRepository.save(new User(email, name, CREATED_AT));
    }

    private OAuthAccount saveOAuthAccount(User user, String subject, String email) {
        return oauthAccountRepository.save(
                new OAuthAccount(
                        user,
                        OAuthProvider.KAKAO,
                        subject,
                        email,
                        CREATED_AT
                )
        );
    }

    private void markUserAsDeleted(User user) {
        jdbcTemplate.update(
                "UPDATE users SET deleted_at = ? WHERE id = ?",
                Timestamp.from(DELETED_AT),
                user.getId()
        );
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
