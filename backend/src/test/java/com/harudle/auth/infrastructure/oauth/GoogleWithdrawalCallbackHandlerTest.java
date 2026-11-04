package com.harudle.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.auth.application.WithdrawalService;
import com.harudle.auth.infrastructure.token.RefreshTokenCookieWriter;
import com.harudle.common.security.AuthProperties;
import java.net.URI;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;

class GoogleWithdrawalCallbackHandlerTest {

    @Test
    void successConsumesRequestAndReturnsWithdrawalResult() throws Exception {
        var f = new Fixture();

        assertThat(f.handler.success(f.request, f.response, f.authentication)).isTrue();

        verify(f.withdrawal).completeGoogle(f.userId, f.authentication, f.client);
        verify(f.cookies).clear(f.response);
        assertThat(f.response.getRedirectedUrl()).isEqualTo("https://harudle.com/withdrawal/callback?withdrawal=success");
        assertThat(f.handler.success(f.request, new MockHttpServletResponse(), f.authentication)).isFalse();
    }

    @Test
    void failureDoesNotClearCookieAndReturnsFailure() throws Exception {
        var f = new Fixture();
        doThrow(new IllegalStateException()).when(f.withdrawal).completeGoogle(f.userId, f.authentication, f.client);

        assertThat(f.handler.success(f.request, f.response, f.authentication)).isTrue();

        verifyNoInteractions(f.cookies);
        assertThat(f.response.getRedirectedUrl()).isEqualTo("https://harudle.com/withdrawal/callback?withdrawal=failed");
    }

    @Test
    @DisplayName("구글 인증 취소도 탈퇴 전용 콜백으로 전달하고 기존 쿠키를 유지한다")
    void authenticationFailureReturnsWithdrawalFailure() throws Exception {
        var f = new Fixture();

        assertThat(f.handler.failure(f.request, f.response)).isTrue();

        assertThat(f.response.getRedirectedUrl()).isEqualTo("https://harudle.com/withdrawal/callback?withdrawal=failed");
        verifyNoInteractions(f.withdrawal, f.cookies);
        assertThat(f.handler.failure(f.request, new MockHttpServletResponse())).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "http://localhost:5173/auth/callback, http://localhost:5173/withdrawal/callback?withdrawal=success",
            "https://dev.harudle.com/auth/callback, https://dev.harudle.com/withdrawal/callback?withdrawal=success",
            "https://www.harudle.com/login/complete?next=home#login, "
                    + "https://www.harudle.com/withdrawal/callback?withdrawal=success"
    })
    @DisplayName("탈퇴 복귀 주소는 환경별 프론트 주소를 유지하고 로그인 경로와 쿼리를 분리한다")
    void withdrawalCallbackUsesFrontendOrigin(String loginCallback, String withdrawalCallback) throws Exception {
        var f = new Fixture(loginCallback);

        assertThat(f.handler.success(f.request, f.response, f.authentication)).isTrue();

        assertThat(f.response.getRedirectedUrl()).isEqualTo(withdrawalCallback);
    }

    @Test
    void regularLoginStateDoesNotTriggerWithdrawal() throws Exception {
        var f = new Fixture();
        f.request.setParameter("state", "another-state");

        assertThat(f.handler.success(f.request, f.response, f.authentication)).isFalse();
        verifyNoInteractions(f.withdrawal, f.cookies);
    }

    private static class Fixture {
        private final UUID userId = UUID.randomUUID();
        private final GoogleWithdrawalAuthorizationState state = new GoogleWithdrawalAuthorizationState(Clock.systemUTC());
        private final OAuth2AuthorizedClientService clients = mock(OAuth2AuthorizedClientService.class);
        private final WithdrawalService withdrawal = mock(WithdrawalService.class);
        private final RefreshTokenCookieWriter cookies = mock(RefreshTokenCookieWriter.class);
        private final AuthProperties properties = mock(AuthProperties.class);
        private final OAuth2AuthenticationToken authentication = mock(OAuth2AuthenticationToken.class);
        private final OAuth2AuthorizedClient client = mock(OAuth2AuthorizedClient.class);
        private final MockHttpServletRequest request = new MockHttpServletRequest();
        private final MockHttpServletResponse response = new MockHttpServletResponse();
        private final GoogleWithdrawalCallbackHandler handler;

        private Fixture() {
            this("https://harudle.com/auth/callback");
        }

        private Fixture(String loginCallback) {
            when(properties.successRedirect()).thenReturn(URI.create(loginCallback));
            when(authentication.getAuthorizedClientRegistrationId()).thenReturn("google");
            when(authentication.getName()).thenReturn("google-user");
            when(clients.loadAuthorizedClient("google", "google-user")).thenReturn(client);
            String requestId = state.begin(request.getSession(), userId);
            state.bind(request.getSession(), requestId, "oauth-state");
            request.setParameter("state", "oauth-state");
            handler = new GoogleWithdrawalCallbackHandler(state, clients, withdrawal, cookies, properties);
        }
    }
}
