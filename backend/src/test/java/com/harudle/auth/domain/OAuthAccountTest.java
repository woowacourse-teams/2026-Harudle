package com.harudle.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OAuthAccountTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-11T10:00:00Z");
    private static final Instant LOGIN_AT = Instant.parse("2026-08-12T10:00:00Z");

    @Test
    @DisplayName("OAuth 계정을 생성한다")
    void createsOAuthAccount() {
        User user = createUser();

        OAuthAccount account = new OAuthAccount(
                user,
                OAuthProvider.KAKAO,
                "12345",
                "old@example.com",
                CREATED_AT
        );

        assertThat(account.getUser()).isSameAs(user);
        assertThat(account.getProvider()).isEqualTo(OAuthProvider.KAKAO);
        assertThat(account.getProviderSubject()).isEqualTo("12345");
        assertThat(account.getProviderEmail()).isEqualTo("old@example.com");
        assertThat(account.getLastLoginAt()).isEqualTo(CREATED_AT);
        assertThat(account.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("재로그인하면 제공자 이메일과 마지막 로그인 시간을 갱신한다")
    void recordsLogin() {
        User user = createUser();
        OAuthAccount account = new OAuthAccount(
                user,
                OAuthProvider.KAKAO,
                "12345",
                "old@example.com",
                CREATED_AT
        );

        account.recordLogin("new@example.com", LOGIN_AT);

        assertThat(account.getProviderEmail()).isEqualTo("new@example.com");
        assertThat(account.getLastLoginAt()).isEqualTo(LOGIN_AT);
        assertThat(account.getUser()).isSameAs(user);
        assertThat(account.getProvider()).isEqualTo(OAuthProvider.KAKAO);
        assertThat(account.getProviderSubject()).isEqualTo("12345");
        assertThat(account.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("재로그인에서 이메일이 제공되지 않으면 기존 제공자 이메일을 유지한다")
    void keepsProviderEmailWhenEmailIsNotProvided() {
        OAuthAccount account = new OAuthAccount(
                createUser(),
                OAuthProvider.KAKAO,
                "12345",
                "old@example.com",
                CREATED_AT
        );

        account.recordLogin(null, LOGIN_AT);

        assertThat(account.getProviderEmail()).isEqualTo("old@example.com");
        assertThat(account.getLastLoginAt()).isEqualTo(LOGIN_AT);
    }

    private User createUser() {
        return new User(
                "user@example.com",
                "하루들",
                CREATED_AT
        );
    }
}
