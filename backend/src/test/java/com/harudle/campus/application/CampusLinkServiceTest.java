package com.harudle.campus.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.auth.application.AccountAccessPolicy;
import com.harudle.auth.application.AccountSuspendedException;
import com.harudle.auth.application.CurrentUserResult;
import com.harudle.auth.application.CurrentUserService;
import com.harudle.auth.domain.OAuthProvider;
import com.harudle.auth.domain.UserRole;
import com.harudle.auth.domain.exception.PublicProfileNotAssignedException;
import com.harudle.auth.infrastructure.token.RefreshTokenGenerator;
import com.harudle.auth.infrastructure.token.RefreshTokenHasher;
import com.harudle.campus.infrastructure.CampusLinkCodeRepository;
import com.harudle.campus.infrastructure.CampusLinkCodeRepository.ConsumedCampusLinkCode;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CampusLinkServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
    private static final String SECRET = "campus-client-secret-for-test";
    private static final String STATE = "s".repeat(43);
    private static final UUID USER_ID = UUID.fromString("6d64421d-6a44-48fa-b917-c1d20f5e5883");
    private static final URI REDIRECT = URI.create("https://campus.harudle.com/auth/harudle/callback");

    private final RefreshTokenHasher hasher = new RefreshTokenHasher();
    private CampusLinkCodeRepository repository;
    private CurrentUserService currentUserService;
    private AccountAccessPolicy accountAccess;
    private CampusLinkService service;

    @BeforeEach
    void setUp() {
        repository = mock(CampusLinkCodeRepository.class);
        currentUserService = mock(CurrentUserService.class);
        accountAccess = mock(AccountAccessPolicy.class);
        service = createService(true);
    }

    @Test
    @DisplayName("닉네임이 있는 회원에게 60초짜리 연결 코드를 발급하고 해시만 저장한다")
    void issuesCode() {
        when(currentUserService.find(USER_ID)).thenReturn(user("눈누난나"));

        IssuedCampusLinkCode issued = service.issue(USER_ID, STATE, NOW);

        assertThat(issued.code()).matches("[A-Za-z0-9_-]{43}");
        assertThat(issued.redirectUri()).isEqualTo(REDIRECT);
        assertThat(issued.expiresAt()).isEqualTo(NOW.plusSeconds(60));
        verify(repository).save(USER_ID, hasher.hash(issued.code()), hasher.hash(STATE), NOW.plusSeconds(60), NOW);
    }

    @Test
    @DisplayName("공개 프로필(닉네임)이 없으면 발급하지 않는다")
    void rejectsIssueWithoutNickname() {
        when(currentUserService.find(USER_ID)).thenReturn(user(null));

        assertThatThrownBy(() -> service.issue(USER_ID, STATE, NOW))
                .isInstanceOf(PublicProfileNotAssignedException.class);
        verify(repository, never()).save(any(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("기능이 꺼져 있으면 발급과 교환 모두 거절한다")
    void rejectsWhenDisabled() {
        CampusLinkService disabled = createService(false);

        assertThatThrownBy(() -> disabled.issue(USER_ID, STATE, NOW))
                .isInstanceOf(CampusLinkDisabledException.class);
        assertThatThrownBy(() -> disabled.exchange(SECRET, "c".repeat(43), STATE, NOW))
                .isInstanceOf(CampusLinkDisabledException.class);
    }

    @Test
    @DisplayName("비밀값과 state가 맞으면 코드를 교환해 회원 ID와 닉네임만 돌려준다")
    void exchangesCode() {
        String code = "c".repeat(43);
        when(repository.consume(hasher.hash(code), NOW))
                .thenReturn(Optional.of(new ConsumedCampusLinkCode(USER_ID, hasher.hash(STATE))));
        when(currentUserService.find(USER_ID)).thenReturn(user("눈누난나"));

        CampusLinkProfile profile = service.exchange(SECRET, code, STATE, NOW);

        assertThat(profile.userId()).isEqualTo(USER_ID);
        assertThat(profile.nickname()).isEqualTo("눈누난나");
        assertThat(profile.profileImageUrl()).isEqualTo(URI.create("https://www.harudle.com/profile-images/v1/5.png"));
    }

    @Test
    @DisplayName("비밀값이 틀리면 코드를 소비하지 않고 거절한다")
    void rejectsWrongClientSecret() {
        assertThatThrownBy(() -> service.exchange("wrong-secret", "c".repeat(43), STATE, NOW))
                .isInstanceOf(InvalidCampusClientException.class);
        assertThatThrownBy(() -> service.exchange(null, "c".repeat(43), STATE, NOW))
                .isInstanceOf(InvalidCampusClientException.class);
        verify(repository, never()).consume(anyString(), any());
    }

    @Test
    @DisplayName("이미 쓴 코드, 만료된 코드, state가 다른 코드는 같은 오류로 거절한다")
    void rejectsUnusableCode() {
        String code = "c".repeat(43);
        when(repository.consume(eq(hasher.hash(code)), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.exchange(SECRET, code, STATE, NOW))
                .isInstanceOf(InvalidCampusLinkCodeException.class);

        String other = "d".repeat(43);
        when(repository.consume(eq(hasher.hash(other)), any()))
                .thenReturn(Optional.of(new ConsumedCampusLinkCode(USER_ID, hasher.hash("x".repeat(43)))));
        assertThatThrownBy(() -> service.exchange(SECRET, other, STATE, NOW))
                .isInstanceOf(InvalidCampusLinkCodeException.class);

        assertThatThrownBy(() -> service.exchange(SECRET, "short", STATE, NOW))
                .isInstanceOf(InvalidCampusLinkCodeException.class);
    }

    @Test
    @DisplayName("발급 뒤 정지된 회원의 코드는 교환하지 않는다")
    void rejectsSuspendedUserOnExchange() {
        String code = "c".repeat(43);
        when(repository.consume(hasher.hash(code), NOW))
                .thenReturn(Optional.of(new ConsumedCampusLinkCode(USER_ID, hasher.hash(STATE))));
        doThrow(new AccountSuspendedException()).when(accountAccess).requireAllowedForRequest(USER_ID);

        assertThatThrownBy(() -> service.exchange(SECRET, code, STATE, NOW))
                .isInstanceOf(InvalidCampusLinkCodeException.class);
    }

    @Test
    @DisplayName("켜져 있을 때 콜백 주소와 비밀값 해시가 없으면 설정 오류다")
    void validatesEnabledProperties() {
        assertThatThrownBy(() -> new CampusLinkProperties(true, null, hasher.hash(SECRET), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CampusLinkProperties(true, REDIRECT, "not-a-hash", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new CampusLinkProperties(false, null, null, null).codeTtl()).isEqualTo(Duration.ofSeconds(60));
    }

    private CampusLinkService createService(boolean enabled) {
        CampusLinkProperties properties = new CampusLinkProperties(enabled, REDIRECT, hasher.hash(SECRET), null);
        return new CampusLinkService(
                properties,
                repository,
                currentUserService,
                accountAccess,
                new RefreshTokenGenerator(),
                hasher
        );
    }

    private CurrentUserResult user(String nickname) {
        URI image = nickname == null ? null : URI.create("https://www.harudle.com/profile-images/v1/5.png");
        return new CurrentUserResult(
                USER_ID,
                "하루들",
                nickname,
                image,
                null,
                "user@example.com",
                UserRole.USER,
                OAuthProvider.KAKAO,
                NOW
        );
    }
}
