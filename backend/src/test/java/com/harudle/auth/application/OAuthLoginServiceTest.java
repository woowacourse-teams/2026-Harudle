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
@Import(OAuthLoginService.class)
class OAuthLoginServiceTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18-alpine");
    private static final Instant CREATED_AT = Instant.parse("2026-08-11T10:00:00Z");
    private static final Instant LOGIN_AT = Instant.parse("2026-08-12T10:00:00Z");
    private static final Instant DELETED_AT = Instant.parse("2026-08-12T09:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL_CONTAINER = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private OAuthLoginService oAuthLoginService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OAuthAccountRepository oAuthAccountRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("처음 로그인한 사용자의 계정과 OAuth 계정을 함께 생성한다")
    void createsNewUserAndOAuthAccount() {
        OAuthLoginCommand command = createCommand(
                "12345",
                "user@example.com",
                "하루들"
        );

        OAuthLoginResult result = oAuthLoginService.login(command, LOGIN_AT);
        flushAndClear();

        User savedUser = userRepository.findById(result.userId()).orElseThrow();
        OAuthAccount savedAccount = findAccount("12345");

        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(oAuthAccountRepository.count()).isEqualTo(1);
        assertThat(savedUser.getPrimaryEmail()).isEqualTo("user@example.com");
        assertThat(savedUser.getName()).isEqualTo("하루들");
        assertThat(savedUser.getCreatedAt()).isEqualTo(LOGIN_AT);
        assertThat(savedAccount.getUser().getId()).isEqualTo(savedUser.getId());
        assertThat(savedAccount.getProvider()).isEqualTo(OAuthProvider.KAKAO);
        assertThat(savedAccount.getProviderSubject()).isEqualTo("12345");
        assertThat(savedAccount.getProviderEmail()).isEqualTo("user@example.com");
        assertThat(savedAccount.getLastLoginAt()).isEqualTo(LOGIN_AT);
    }

    @Test
    @DisplayName("기존 OAuth 계정 로그인은 사용자 재사용과 기록 갱신을 보장한다")
    void logsInExistingUser() {
        User existingUser = saveUser(
                "old@example.com",
                "서비스 이름",
                CREATED_AT
        );
        saveAccount(
                existingUser,
                "12345",
                "old@example.com",
                CREATED_AT
        );
        flushAndClear();

        OAuthLoginCommand command = createCommand(
                "12345",
                "new@example.com",
                "새 카카오 닉네임"
        );
        OAuthLoginResult result = oAuthLoginService.login(command, LOGIN_AT);
        flushAndClear();

        User savedUser = userRepository.findById(result.userId()).orElseThrow();
        OAuthAccount savedAccount = findAccount("12345");

        assertThat(result.userId()).isEqualTo(existingUser.getId());
        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(oAuthAccountRepository.count()).isEqualTo(1);
        assertThat(savedUser.getName()).isEqualTo("서비스 이름");
        assertThat(savedAccount.getProviderEmail()).isEqualTo("new@example.com");
        assertThat(savedAccount.getLastLoginAt()).isEqualTo(LOGIN_AT);
        assertThat(savedAccount.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("이메일이 같아도 OAuth 사용자 식별자가 다르면 새로운 사용자를 생성한다")
    void createsNewUserWhenProviderSubjectIsDifferent() {
        User existingUser = saveUser(
                "same@example.com",
                "기존 사용자",
                CREATED_AT
        );
        saveAccount(
                existingUser,
                "11111",
                "same@example.com",
                CREATED_AT
        );
        flushAndClear();

        OAuthLoginCommand command = createCommand(
                "22222",
                "same@example.com",
                "신규 사용자"
        );
        OAuthLoginResult result = oAuthLoginService.login(command, LOGIN_AT);
        flushAndClear();

        OAuthAccount newAccount = findAccount("22222");

        assertThat(result.userId()).isNotEqualTo(existingUser.getId());
        assertThat(userRepository.count()).isEqualTo(2);
        assertThat(oAuthAccountRepository.count()).isEqualTo(2);
        assertThat(newAccount.getUser().getId()).isEqualTo(result.userId());
    }

    @Test
    @DisplayName("신규 회원의 이메일이 없으면 회원을 생성하지 않는다")
    void rejectsNewUserWithoutEmail() {
        OAuthLoginCommand command = createCommand(
                "12345",
                null,
                "하루들"
        );

        assertThatThrownBy(() -> oAuthLoginService.login(command, LOGIN_AT))
                .isInstanceOf(RequiredOAuthProfileException.class)
                .hasMessage("이메일이 필요합니다.");

        assertThat(userRepository.count()).isZero();
        assertThat(oAuthAccountRepository.count()).isZero();
    }

    @Test
    @DisplayName("신규 회원의 닉네임이 없으면 회원을 생성하지 않는다")
    void rejectsNewUserWithoutDisplayName() {
        OAuthLoginCommand command = createCommand(
                "12345",
                "user@example.com",
                null
        );

        assertThatThrownBy(() -> oAuthLoginService.login(command, LOGIN_AT))
                .isInstanceOf(RequiredOAuthProfileException.class)
                .hasMessage("닉네임이 필요합니다.");

        assertThat(userRepository.count()).isZero();
        assertThat(oAuthAccountRepository.count()).isZero();
    }

    @Test
    @DisplayName("기존 OAuth 계정 로그인에서 이메일이 없으면 기존 이메일을 유지한다")
    void keepsExistingProviderEmailWhenProviderEmailIsMissing() {
        User existingUser = saveUser(
                "old@example.com",
                "서비스 이름",
                CREATED_AT
        );
        saveAccount(
                existingUser,
                "12345",
                "old@example.com",
                CREATED_AT
        );
        flushAndClear();

        OAuthLoginCommand command = createCommand(
                "12345",
                null,
                null
        );
        oAuthLoginService.login(command, LOGIN_AT);
        flushAndClear();

        OAuthAccount savedAccount = findAccount("12345");

        assertThat(savedAccount.getProviderEmail()).isEqualTo("old@example.com");
        assertThat(savedAccount.getLastLoginAt()).isEqualTo(LOGIN_AT);
    }

    @Test
    @DisplayName("탈퇴한 사용자는 로그인할 수 없고 로그인 기록도 갱신하지 않는다")
    void rejectsDeletedUser() {
        User deletedUser = saveUser(
                "old@example.com",
                "탈퇴 사용자",
                CREATED_AT
        );
        saveAccount(
                deletedUser,
                "12345",
                "old@example.com",
                CREATED_AT
        );
        flushAndClear();
        markUserAsDeleted(deletedUser);
        entityManager.clear();

        OAuthLoginCommand command = createCommand(
                "12345",
                "new@example.com",
                "새 카카오 닉네임"
        );

        assertThatThrownBy(() -> oAuthLoginService.login(command, LOGIN_AT))
                .isInstanceOf(InactiveUserException.class);

        entityManager.clear();
        OAuthAccount savedAccount = findAccount("12345");

        assertThat(savedAccount.getProviderEmail()).isEqualTo("old@example.com");
        assertThat(savedAccount.getLastLoginAt()).isEqualTo(CREATED_AT);
        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(oAuthAccountRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("로그인 명령이 없으면 로그인할 수 없다")
    void rejectsNullCommand() {
        assertThatThrownBy(() -> oAuthLoginService.login(null, LOGIN_AT))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("로그인 시간이 없으면 로그인할 수 없다")
    void rejectsNullLoginTime() {
        OAuthLoginCommand command = createCommand(
                "12345",
                "user@example.com",
                "하루들"
        );

        assertThatThrownBy(() -> oAuthLoginService.login(command, null))
                .isInstanceOf(NullPointerException.class);
    }

    private OAuthLoginCommand createCommand(
            String providerSubject,
            String providerEmail,
            String displayName
    ) {
        return new OAuthLoginCommand(
                OAuthProvider.KAKAO,
                providerSubject,
                providerEmail,
                displayName
        );
    }

    private User saveUser(
            String email,
            String name,
            Instant now
    ) {
        return userRepository.save(
                new User(email, name, now)
        );
    }

    private OAuthAccount saveAccount(
            User user,
            String providerSubject,
            String providerEmail,
            Instant now
    ) {
        return oAuthAccountRepository.save(
                new OAuthAccount(
                        user,
                        OAuthProvider.KAKAO,
                        providerSubject,
                        providerEmail,
                        now
                )
        );
    }

    private OAuthAccount findAccount(String providerSubject) {
        return oAuthAccountRepository
                .findByProviderAndProviderSubject(
                        OAuthProvider.KAKAO,
                        providerSubject
                )
                .orElseThrow();
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
