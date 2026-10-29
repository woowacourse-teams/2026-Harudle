package com.harudle.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.harudle.auth.application.OAuthLoginService;
import com.harudle.auth.application.RefreshTokenService;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.infrastructure.oauth.AppleLoginCommandMapper;
import com.harudle.auth.infrastructure.oauth.AppleNativeTokenVerifier;
import com.harudle.auth.infrastructure.token.RefreshTokenCookieWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AppleNativeLoginControllerTest {
    private final AppleNativeTokenVerifier verifier = mock(AppleNativeTokenVerifier.class);
    private final AppleNativeLoginController controller = new AppleNativeLoginController(
            Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC), verifier,
            mock(AppleLoginCommandMapper.class), mock(OAuthLoginService.class), mock(UserRepository.class),
            mock(RefreshTokenService.class), mock(RefreshTokenCookieWriter.class));

    @Test
    void nonceIsBoundToSessionAndConsumedAfterFailure() {
        var request = new MockHttpServletRequest();
        String nonce = controller.nonce(request).getBody().nonce();
        assertThat(nonce).isNotBlank();
        var login = new AppleNativeLoginController.LoginRequest("code", "token");
        var otherRequest = new MockHttpServletRequest();
        assertThatThrownBy(() -> controller.login(login, otherRequest, new MockHttpServletResponse()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(verifier, never()).verify(any(), any(), any());
        org.mockito.Mockito.when(verifier.verify("code", "token", nonce))
                .thenThrow(new IllegalArgumentException("invalid"));
        assertThatThrownBy(() -> controller.login(login, request, new MockHttpServletResponse()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.login(login, request, new MockHttpServletResponse()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(verifier).verify("code", "token", nonce);
    }
}
