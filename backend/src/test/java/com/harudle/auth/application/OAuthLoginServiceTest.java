package com.harudle.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.TestcontainersConfiguration;
import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.OAuthProvider;
import com.harudle.auth.domain.User;
import com.harudle.auth.domain.exception.NicknamePoolExhaustedException;
import com.harudle.auth.infrastructure.OAuthAccountRepository;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.infrastructure.nickname.NicknameCandidateRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, OAuthLoginService.class, NicknameCandidateRepository.class})
class OAuthLoginServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-11T10:00:00Z");
    private static final Instant LOGIN_AT = Instant.parse("2026-08-12T10:00:00Z");
    private static final Instant DELETED_AT = Instant.parse("2026-08-12T09:00:00Z");

    @Autowired
    private OAuthLoginService oAuthLoginService;

    @Autowired
    private UserRepository userRepository;

    @MockitoSpyBean
    private OAuthAccountRepository oAuthAccountRepository;

    @MockitoBean
    private ProfileImageCodeGenerator profileImageCodeGenerator;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        when(profileImageCodeGenerator.generate()).thenReturn(3);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 5})
    @DisplayName("소셜 이름과 자동 배정한 공개 닉네임으로 신규 회원과 OAuth 계정을 생성한다")
    void createsNewUserAndOAuthAccount(int imageCode) {
        when(profileImageCodeGenerator.generate()).thenReturn(imageCode);
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
        assertThat(savedUser.getNickname()).isNotBlank();
        assertThat(savedUser.getName()).isEqualTo("하루들");
        assertThat(savedUser.getNickname()).isNotEqualTo("하루들");
        assertThat(savedUser.getProfileImageCode()).isEqualTo((short) imageCode);
        assertThat(findCandidateOwner(savedUser.getNickname())).isEqualTo(savedUser.getId());
        assertThat(savedUser.getCreatedAt()).isEqualTo(LOGIN_AT);
        assertThat(savedAccount.getUser().getId()).isEqualTo(savedUser.getId());
        assertThat(savedAccount.getProvider()).isEqualTo(OAuthProvider.KAKAO);
        assertThat(savedAccount.getProviderSubject()).isEqualTo("12345");
        assertThat(savedAccount.getProviderEmail()).isEqualTo("user@example.com");
        assertThat(savedAccount.getLastLoginAt()).isEqualTo(LOGIN_AT);
        verify(profileImageCodeGenerator, times(1)).generate();
    }

    @Test
    @DisplayName("재로그인하면 기존 이름과 공개 프로필을 유지하고 로그인 기록을 갱신한다")
    void logsInExistingUser() {
        User existingUser = saveUser(
                "old@example.com",
                "서비스 이름",
                CREATED_AT
        );
        existingUser.assignInitialPublicProfile("초록", 5, CREATED_AT);
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
        assertThat(savedUser.getNickname()).isEqualTo("초록");
        assertThat(savedUser.getProfileImageCode()).isEqualTo((short) 5);
        verifyNoInteractions(profileImageCodeGenerator);
        assertThat(savedAccount.getProviderEmail()).isEqualTo("new@example.com");
        assertThat(savedAccount.getLastLoginAt()).isEqualTo(LOGIN_AT);
        assertThat(savedAccount.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("다시 로그인해도 최초 닉네임·이미지·후보 점유를 유지한다")
    void preservesPublicProfileOnRepeatedLogin() {
        OAuthLoginCommand command = createCommand("12345", "user@example.com", "하루들");
        OAuthLoginResult first = oAuthLoginService.login(command, LOGIN_AT);
        flushAndClear();
        String nickname = userRepository.findById(first.userId()).orElseThrow().getNickname();

        OAuthLoginResult second = oAuthLoginService.login(command, LOGIN_AT.plusSeconds(60));
        flushAndClear();
        User user = userRepository.findById(second.userId()).orElseThrow();

        assertThat(second.userId()).isEqualTo(first.userId());
        assertThat(user.getName()).isEqualTo("하루들");
        assertThat(user.getNickname()).isEqualTo(nickname);
        assertThat(user.getProfileImageCode()).isEqualTo((short) 3);
        assertThat(findCandidateOwner(nickname)).isEqualTo(user.getId());
        assertThat(assignedCandidateCount()).isEqualTo(1);
        verify(profileImageCodeGenerator, times(1)).generate();
    }

    @Test
    @DisplayName("서로 다른 신규 OAuth 회원에게 서로 다른 후보를 배정한다")
    void assignsDifferentNicknamesToNewUsers() {
        OAuthLoginResult first = oAuthLoginService.login(
                createCommand("11111", "same@example.com", "첫회원"), LOGIN_AT
        );
        OAuthLoginResult second = oAuthLoginService.login(
                createCommand("22222", "same@example.com", "둘째회원"), LOGIN_AT
        );
        flushAndClear();
        User firstUser = userRepository.findById(first.userId()).orElseThrow();
        User secondUser = userRepository.findById(second.userId()).orElseThrow();

        assertThat(firstUser.getNickname()).isNotEqualTo(secondUser.getNickname());
        assertThat(findCandidateOwner(firstUser.getNickname())).isEqualTo(firstUser.getId());
        assertThat(findCandidateOwner(secondUser.getNickname())).isEqualTo(secondUser.getId());
        assertThat(assignedCandidateCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("후보가 소진되면 신규 회원과 OAuth 계정을 저장하지 않는다")
    void rejectsSignupWhenCandidatesAreExhausted() {
        jdbcTemplate.update("DELETE FROM nickname_candidates");

        assertThatThrownBy(() -> oAuthLoginService.login(
                createCommand("12345", "user@example.com", "하루들"), LOGIN_AT
        )).isInstanceOf(NicknamePoolExhaustedException.class);
        assertThat(userRepository.count()).isZero();
        assertThat(oAuthAccountRepository.count()).isZero();
        verifyNoInteractions(profileImageCodeGenerator);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("OAuth 저장 실패는 이미 저장한 회원과 후보 점유까지 롤백한다")
    void rollsBackUserAndCandidateWhenOAuthSaveFails() {
        doAnswer(invocation -> {
            assertThat(userRepository.count()).isEqualTo(1);
            assertThat(assignedCandidateCount()).isEqualTo(1);
            throw new IllegalStateException("OAuth 계정 저장 실패");
        }).when(oAuthAccountRepository).save(any(OAuthAccount.class));

        assertThatThrownBy(() -> oAuthLoginService.login(
                createCommand("12345", "user@example.com", "하루들"), LOGIN_AT
        )).isInstanceOf(IllegalStateException.class).hasMessage("OAuth 계정 저장 실패");

        assertThat(userRepository.count()).isZero();
        assertThat(oAuthAccountRepository.count()).isZero();
        assertThat(assignedCandidateCount()).isZero();
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
    @DisplayName("카카오와 구글의 고유값과 이메일이 같아도 별도 회원을 생성한다")
    void createsSeparateGoogleUserWhenKakaoIdentityMatches() {
        OAuthLoginResult kakaoResult = oAuthLoginService.login(
                createCommand("12345", "same@example.com", "카카오 이름"), LOGIN_AT
        );
        OAuthLoginCommand googleCommand = new OAuthLoginCommand(
                OAuthProvider.GOOGLE,
                "12345",
                "same@example.com",
                "구글 이름"
        );

        OAuthLoginResult googleResult = oAuthLoginService.login(googleCommand, LOGIN_AT);
        flushAndClear();

        User kakaoUser = userRepository.findById(kakaoResult.userId()).orElseThrow();
        User googleUser = userRepository.findById(googleResult.userId()).orElseThrow();

        assertThat(googleResult.userId()).isNotEqualTo(kakaoResult.userId());
        assertThat(userRepository.count()).isEqualTo(2);
        assertThat(oAuthAccountRepository.count()).isEqualTo(2);
        assertThat(googleUser.getName()).isEqualTo("구글 이름");
        assertThat(kakaoUser.getName()).isEqualTo("카카오 이름");
        assertThat(googleUser.getNickname()).isNotEqualTo(kakaoUser.getNickname());
        assertThat(findAccount(OAuthProvider.GOOGLE, "12345").getUser().getId()).isEqualTo(googleUser.getId());
        assertThat(findAccount("12345").getUser().getId()).isEqualTo(kakaoUser.getId());
        assertThat(assignedCandidateCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("구글 재로그인에서 이름이 없어도 기존 회원과 이름, 공개 닉네임을 유지한다")
    void logsInExistingGoogleUserWithoutName() {
        OAuthLoginCommand firstCommand = new OAuthLoginCommand(
                OAuthProvider.GOOGLE,
                "12345",
                "old@example.com",
                "구글 이름"
        );
        OAuthLoginResult first = oAuthLoginService.login(firstCommand, LOGIN_AT);
        flushAndClear();
        String nickname = userRepository.findById(first.userId()).orElseThrow().getNickname();
        OAuthLoginCommand secondCommand = new OAuthLoginCommand(
                OAuthProvider.GOOGLE,
                "12345",
                "new@example.com",
                null
        );

        OAuthLoginResult second = oAuthLoginService.login(secondCommand, LOGIN_AT.plusSeconds(60));
        flushAndClear();

        User savedUser = userRepository.findById(second.userId()).orElseThrow();
        OAuthAccount savedAccount = findAccount(OAuthProvider.GOOGLE, "12345");

        assertThat(second.userId()).isEqualTo(first.userId());
        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(oAuthAccountRepository.count()).isEqualTo(1);
        assertThat(savedUser.getName()).isEqualTo("구글 이름");
        assertThat(savedUser.getNickname()).isEqualTo(nickname);
        assertThat(savedAccount.getProviderEmail()).isEqualTo("new@example.com");
        assertThat(savedAccount.getLastLoginAt()).isEqualTo(LOGIN_AT.plusSeconds(60));
        assertThat(assignedCandidateCount()).isEqualTo(1);
        verify(profileImageCodeGenerator, times(1)).generate();
    }

    @Test
    @DisplayName("구글 이름과 이메일이 없어도 자동 닉네임으로 신규 회원을 생성한다")
    void createsGoogleUserWithoutNameAndEmail() {
        OAuthLoginCommand command = new OAuthLoginCommand(
                OAuthProvider.GOOGLE,
                "12345",
                null,
                null
        );

        OAuthLoginResult result = oAuthLoginService.login(command, LOGIN_AT);
        flushAndClear();

        User savedUser = userRepository.findById(result.userId()).orElseThrow();
        OAuthAccount savedAccount = findAccount(OAuthProvider.GOOGLE, "12345");

        assertThat(savedUser.getNickname()).isNotBlank();
        assertThat(savedUser.getName()).isEqualTo(savedUser.getNickname());
        assertThat(savedUser.getPrimaryEmail()).isNull();
        assertThat(savedAccount.getProviderEmail()).isNull();
        assertThat(savedAccount.getUser().getId()).isEqualTo(savedUser.getId());
    }

    @Test
    @DisplayName("소셜 이름과 이메일이 없어도 자동 닉네임을 기본 이름으로 회원을 생성한다")
    void createsNewUserWithoutEmail() {
        OAuthLoginCommand command = createCommand(
                "12345",
                null,
                null
        );

        OAuthLoginResult result = oAuthLoginService.login(command, LOGIN_AT);
        flushAndClear();

        User savedUser = userRepository.findById(result.userId()).orElseThrow();
        OAuthAccount savedAccount = findAccount("12345");

        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(oAuthAccountRepository.count()).isEqualTo(1);
        assertThat(savedUser.getPrimaryEmail()).isNull();
        assertThat(savedUser.getNickname()).isNotBlank();
        assertThat(savedUser.getName()).isEqualTo(savedUser.getNickname());
        assertThat(savedAccount.getProviderEmail()).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    @DisplayName("소셜 이름이 없어도 자동 배정한 닉네임으로 신규 회원을 생성한다")
    void createsNewUserWithoutDisplayName(String displayName) {
        OAuthLoginCommand command = createCommand(
                "12345",
                "user@example.com",
                displayName
        );

        OAuthLoginResult result = oAuthLoginService.login(command, LOGIN_AT);
        flushAndClear();
        User savedUser = userRepository.findById(result.userId()).orElseThrow();
        OAuthAccount savedAccount = findAccount("12345");

        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(oAuthAccountRepository.count()).isEqualTo(1);
        assertThat(savedUser.getNickname()).isNotBlank();
        assertThat(savedUser.getName()).isEqualTo(savedUser.getNickname());
        assertThat(findCandidateOwner(savedUser.getNickname())).isEqualTo(savedUser.getId());
        assertThat(savedAccount.getUser().getId()).isEqualTo(savedUser.getId());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    @DisplayName("재로그인에서 소셜 이름과 이메일이 없으면 기존 이름과 공개 프로필을 유지한다")
    void keepsExistingProviderProfileWhenProfileIsMissing(String displayName) {
        User existingUser = saveUser(
                "old@example.com",
                "서비스 이름",
                CREATED_AT
        );
        existingUser.assignInitialPublicProfile("초록", 5, CREATED_AT);
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
                displayName
        );
        oAuthLoginService.login(command, LOGIN_AT);
        flushAndClear();

        OAuthAccount savedAccount = findAccount("12345");

        assertThat(savedAccount.getProviderEmail()).isEqualTo("old@example.com");
        assertThat(savedAccount.getUser().getName()).isEqualTo("서비스 이름");
        assertThat(savedAccount.getUser().getNickname()).isEqualTo("초록");
        assertThat(savedAccount.getUser().getProfileImageCode()).isEqualTo((short) 5);
        assertThat(savedAccount.getLastLoginAt()).isEqualTo(LOGIN_AT);
        verifyNoInteractions(profileImageCodeGenerator);
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
        return findAccount(OAuthProvider.KAKAO, providerSubject);
    }

    private OAuthAccount findAccount(OAuthProvider provider, String providerSubject) {
        return oAuthAccountRepository
                .findByProviderAndProviderSubject(
                        provider,
                        providerSubject
                )
                .orElseThrow();
    }

    private UUID findCandidateOwner(String nickname) {
        return jdbcTemplate.queryForObject(
                "SELECT assigned_user_id FROM nickname_candidates WHERE nickname = ?",
                UUID.class,
                nickname
        );
    }

    private long assignedCandidateCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM nickname_candidates WHERE assigned_user_id IS NOT NULL",
                Long.class
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
