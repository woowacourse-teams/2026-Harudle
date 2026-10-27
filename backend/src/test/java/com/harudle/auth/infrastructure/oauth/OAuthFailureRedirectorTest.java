package com.harudle.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.harudle.common.security.AuthProperties;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

class OAuthFailureRedirectorTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "https://harudle.example/auth/callback|https://harudle.example/auth/callback?error=account_suspended",
            "https://harudle.example/auth/callback?error=oauth_failed"
                    + "|https://harudle.example/auth/callback?error=account_suspended",
            "https://harudle.example/auth/callback?returnTo=%2Ffeed%3Fcategory%3D1&error=old#login%20result"
                    + "|https://harudle.example/auth/callback?returnTo=%2Ffeed%3Fcategory%3D1"
                    + "&error=account_suspended#login%20result",
            "https://harudle.example/auth/callback?error=old&keep=a%26b&error=older&er%72or=encoded#section"
                    + "|https://harudle.example/auth/callback?keep=a%26b&error=account_suspended#section"
    })
    @DisplayName("정지 계정만 오류 query 하나를 치환하고 기존 경로와 인코딩된 다른 query 및 fragment를 보존한다")
    void replaceOnlyTheErrorQueryForSuspendedAccounts(String configured, String expected) throws Exception {
        var redirector = redirector(configured);
        var response = new MockHttpServletResponse();

        redirector.redirect(response, OAuthFailureReason.ACCOUNT_SUSPENDED);

        assertThat(response.getRedirectedUrl()).isEqualTo(expected);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @ParameterizedTest
    @EnumSource(value = OAuthFailureReason.class, names = "ACCOUNT_SUSPENDED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("기타 OAuth 실패는 중복 query와 fragment를 포함한 기존 설정 URL을 그대로 유지한다")
    void preserveTheConfiguredRedirectForEveryOtherFailure(OAuthFailureReason reason) throws Exception {
        String configured = "https://harudle.example/auth/callback?error=oauth_failed&error=legacy&keep=%2F#result";
        var redirector = redirector(configured);
        var response = new MockHttpServletResponse();

        redirector.redirect(response, reason);

        assertThat(response.getRedirectedUrl()).isEqualTo(configured);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }

    private OAuthFailureRedirector redirector(String failureRedirect) {
        AuthProperties properties = mock(AuthProperties.class);
        when(properties.failureRedirect()).thenReturn(URI.create(failureRedirect));
        return new OAuthFailureRedirector(properties);
    }
}
