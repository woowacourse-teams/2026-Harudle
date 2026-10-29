package com.harudle.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.auth.application.IssuedRefreshToken;
import com.harudle.auth.application.OAuthLoginCommand;
import com.harudle.auth.application.OAuthLoginResult;
import com.harudle.auth.application.OAuthLoginService;
import com.harudle.auth.application.RefreshTokenService;
import com.harudle.auth.domain.OAuthProvider;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.infrastructure.oauth.AppleLoginCommandMapper;
import com.harudle.auth.infrastructure.oauth.AppleNativeTokenVerifier;
import com.harudle.auth.infrastructure.oauth.AppleNativeTokenVerifier.AppleProviderException;
import com.harudle.auth.infrastructure.oauth.AppleNativeTokenVerifier.InvalidAuthorizationCodeException;
import com.harudle.auth.infrastructure.token.RefreshTokenCookieWriter;
import com.harudle.common.security.AuthProperties;
import com.harudle.common.security.RefreshTokenProperties;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class AppleNativeLoginControllerTest {
    private static final String PATH = "/api/v1/auth/apple/native";
    private static final String LOGIN_BODY = "{\"authorizationCode\":\"code\",\"identityToken\":\"token\"}";
    private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");

    private final Clock clock = mock(Clock.class);
    private final AppleNativeTokenVerifier verifier = mock(AppleNativeTokenVerifier.class);
    private final OAuthLoginService loginService = mock(OAuthLoginService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final RefreshTokenService tokens = mock(RefreshTokenService.class);

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        var properties = new AuthProperties(null, null, null, null,
                new RefreshTokenProperties("refresh_token", "/api/v1/auth", true, "None", Duration.ofDays(30)));
        var controller = new AppleNativeLoginController(clock, verifier,
                new AppleLoginCommandMapper(new ObjectMapper()), loginService, users, tokens,
                new RefreshTokenCookieWriter(properties));
        RestAssuredMockMvc.mockMvc(MockMvcBuilders.standaloneSetup(controller).build());
    }

    @AfterEach
    void tearDown() {
        RestAssuredMockMvc.reset();
    }

    @Test
    void nonceIsBoundToSessionAndConsumedAfterFailure() {
        var session = new MockHttpSession();
        var nonceResponse = nonce(session);
        String nonce = nonceResponse.jsonPath().getString("nonce");
        assertThat(nonceResponse.statusCode()).isEqualTo(200);
        assertThat(nonceResponse.header(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(nonce).isNotBlank();

        assertThat(login(new MockHttpSession()).statusCode()).isEqualTo(400);
        verify(verifier, never()).verify(any(), any(), any());
        when(verifier.verify("code", "token", nonce)).thenThrow(new IllegalArgumentException("invalid"));
        assertThat(login(session).statusCode()).isEqualTo(400);
        assertThat(login(session).statusCode()).isEqualTo(400);
        verify(verifier).verify("code", "token", nonce);
    }

    @Test
    void loginUsesVerifiedClaimsAndIssuesRefreshCookie() {
        var session = new MockHttpSession();
        String nonce = nonce(session).jsonPath().getString("nonce");
        var apple = Jwt.withTokenValue("verified").header("alg", "RS256")
                .subject("apple-user").claim("email", "apple@example.com").build();
        var command = new OAuthLoginCommand(OAuthProvider.APPLE, "apple-user", "apple@example.com", null);
        var user = new User("apple@example.com", "user", NOW);
        var issued = new IssuedRefreshToken("refresh-value", NOW.plus(Duration.ofDays(30)));
        when(verifier.verify("code", "token", nonce)).thenReturn(apple);
        when(loginService.login(command, NOW)).thenReturn(new OAuthLoginResult(user.getId()));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(tokens.issue(user, NOW)).thenReturn(issued);

        var response = login(session);

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.header(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.header(HttpHeaders.SET_COOKIE))
                .contains("refresh_token=refresh-value", "HttpOnly", "Secure", "SameSite=None");
        verify(loginService).login(command, NOW);
        verify(users).findById(user.getId());
        verify(tokens).issue(user, NOW);
    }

    @Test
    void nonceExpiresAtItsExactDeadline() {
        var session = new MockHttpSession();
        nonce(session);
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofMinutes(5)));

        assertThat(login(session).statusCode()).isEqualTo(400);
        verifyNoInteractions(verifier);
    }

    @ParameterizedTest
    @MethodSource("verificationErrors")
    void mapsVerificationErrorsToHttpStatus(RuntimeException exception, int expectedStatus) {
        var session = new MockHttpSession();
        String nonce = nonce(session).jsonPath().getString("nonce");
        when(verifier.verify("code", "token", nonce)).thenThrow(exception);

        var response = login(session);

        assertThat(response.statusCode()).isEqualTo(expectedStatus);
        assertThat(response.header(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        verifyNoInteractions(loginService, users, tokens);
    }

    private static Stream<Arguments> verificationErrors() {
        return Stream.of(
                Arguments.of(new InvalidAuthorizationCodeException(), 400),
                Arguments.of(new IllegalArgumentException(), 400),
                Arguments.of(new AppleProviderException(), 502)
        );
    }

    private MockMvcResponse nonce(MockHttpSession session) {
        return RestAssuredMockMvc.given().postProcessors(request -> {
            request.setSession(session);
            return request;
        }).post(PATH + "/nonce");
    }

    private MockMvcResponse login(MockHttpSession session) {
        return RestAssuredMockMvc.given().postProcessors(request -> {
            request.setSession(session);
            return request;
        }).contentType(MediaType.APPLICATION_JSON_VALUE).body(LOGIN_BODY).post(PATH + "/login");
    }
}
