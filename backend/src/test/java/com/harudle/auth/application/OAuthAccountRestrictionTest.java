package com.harudle.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.auth.application.port.AccountRestrictionReader;
import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.OAuthProvider;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.OAuthAccountRepository;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.infrastructure.nickname.NicknameCandidateRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class OAuthAccountRestrictionTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-23T00:00:00Z");
    private final UserRepository users = mock(UserRepository.class);
    private final OAuthAccountRepository accounts = mock(OAuthAccountRepository.class);
    private final NicknameCandidateRepository nicknames = mock(NicknameCandidateRepository.class);
    private final ProfileImageCodeGenerator images = mock(ProfileImageCodeGenerator.class);
    private final AccountRestrictionReader restrictions = mock(AccountRestrictionReader.class);
    private final OAuthLoginService logins = new OAuthLoginService(users, accounts, nicknames, images,
            new AccountAccessPolicy(restrictions));

    @Test
    @DisplayName("정지된 기존 OAuth 회원은 로그인 기록과 공개 프로필을 바꾸지 않고 거절한다")
    void rejectsBeforeChangingLoginState() {
        User user = new User("test@example.invalid", "회원", CREATED_AT);
        OAuthAccount account = new OAuthAccount(
                user, OAuthProvider.KAKAO, "subject", "old@example.invalid", CREATED_AT);
        when(accounts.findByProviderAndProviderSubject(OAuthProvider.KAKAO, "subject"))
                .thenReturn(Optional.of(account));
        when(restrictions.isSuspended(user.getId())).thenReturn(true);
        var command = new OAuthLoginCommand(OAuthProvider.KAKAO, "subject", "new@example.invalid", "이름");

        assertThatThrownBy(() -> logins.login(command, CREATED_AT.plusSeconds(60)))
                .isInstanceOf(AccountSuspendedException.class);
        assertThat(account.getLastLoginAt()).isEqualTo(CREATED_AT);
        assertThat(account.getProviderEmail()).isEqualTo("old@example.invalid");
        verifyNoInteractions(users, nicknames, images);
    }

    @Test
    @DisplayName("제재 조회 장애도 OAuth 로그인 기록을 갱신하거나 정상 로그인으로 바꾸지 않는다")
    void rejectsUnavailableRestrictionRead() {
        User user = new User("test@example.invalid", "회원", CREATED_AT);
        OAuthAccount account = new OAuthAccount(
                user, OAuthProvider.KAKAO, "subject", "old@example.invalid", CREATED_AT);
        when(accounts.findByProviderAndProviderSubject(OAuthProvider.KAKAO, "subject"))
                .thenReturn(Optional.of(account));
        when(restrictions.isSuspended(user.getId())).thenThrow(new DataAccessResourceFailureException("조회 실패"));
        var command = new OAuthLoginCommand(OAuthProvider.KAKAO, "subject", "new@example.invalid", "이름");

        assertThatThrownBy(() -> logins.login(command, CREATED_AT.plusSeconds(60)))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(account.getLastLoginAt()).isEqualTo(CREATED_AT);
        verifyNoInteractions(users, nicknames, images);
    }
}
