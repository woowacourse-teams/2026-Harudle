package com.harudle.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.common.security.AuthTimeConfiguration;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.web.util.UriComponentsBuilder;

class AppleOAuthConfigurationTest {

    @TempDir
    private Path directory;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OAuth2ClientAutoConfiguration.class))
            .withUserConfiguration(AppleOAuthConfiguration.class, AuthTimeConfiguration.class)
            .withPropertyValues(
                    "spring.security.oauth2.client.registration.google.client-id=google-client",
                    "spring.security.oauth2.client.registration.google.client-secret=google-secret",
                    "spring.security.oauth2.client.registration.kakao.client-id=kakao-client",
                    "spring.security.oauth2.client.registration.kakao.client-secret=kakao-secret",
                    "spring.security.oauth2.client.registration.kakao.client-authentication-method=client_secret_post",
                    "spring.security.oauth2.client.registration.kakao.authorization-grant-type=authorization_code",
                    "spring.security.oauth2.client.registration.kakao.redirect-uri=https://www.harudle.com/login/oauth2/code/kakao",
                    "spring.security.oauth2.client.provider.kakao.authorization-uri=https://kauth.kakao.com/oauth/authorize",
                    "spring.security.oauth2.client.provider.kakao.token-uri=https://kauth.kakao.com/oauth/token",
                    "spring.security.oauth2.client.provider.kakao.user-info-uri=https://kapi.kakao.com/v2/user/me",
                    "spring.security.oauth2.client.provider.kakao.user-name-attribute=id"
            );

    @Test
    @DisplayName("Apple을 끄면 개인 키 설정 없이 기존 제공자만 등록한다")
    void keepsExistingProvidersWhenDisabled() {
        runner.withPropertyValues("app.auth.apple.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ClientRegistrationRepository.class)
                    .doesNotHaveBean(AppleClientSecretGenerator.class)
                    .doesNotHaveBean(OAuth2AuthorizationRequestResolver.class);
            var registrations = context.getBean(ClientRegistrationRepository.class);
            assertThat(registrations.findByRegistrationId("google")).isNotNull();
            assertThat(registrations.findByRegistrationId("kakao")).isNotNull();
            assertThat(registrations.findByRegistrationId("apple")).isNull();
        });
    }

    @Test
    @DisplayName("Apple을 켜도 기존 제공자를 유지하고 Apple 요청에만 form_post를 추가한다")
    void customizesOnlyAppleAuthorizationRequest() throws Exception {
        enabledRunner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ClientRegistrationRepository.class)
                    .hasSingleBean(AppleClientSecretGenerator.class);
            var registrations = context.getBean(ClientRegistrationRepository.class);
            var resolver = context.getBean(OAuth2AuthorizationRequestResolver.class);
            var apple = registrations.findByRegistrationId("apple");
            assertThat(apple.getProviderDetails().getIssuerUri()).isEqualTo("https://appleid.apple.com");
            assertThat(apple.getProviderDetails().getJwkSetUri()).isEqualTo("https://appleid.apple.com/auth/keys");
            assertThat(apple.getScopes()).containsExactlyInAnyOrder("openid", "email");
            for (String provider : new String[]{"apple", "google", "kakao"}) {
                var request = new MockHttpServletRequest("GET", "/oauth2/authorization/" + provider);
                request.setServletPath(request.getRequestURI());
                var authorization = resolver.resolve(request);
                assertThat(authorization.getState()).isNotBlank();
                if (provider.equals("apple")) {
                    assertThat(authorization.getAdditionalParameters()).containsEntry("response_mode", "form_post");
                    assertThat(authorization.getAdditionalParameters().get("nonce")).isNotNull();
                } else {
                    assertThat(authorization.getAdditionalParameters()).doesNotContainKey("response_mode");
                }
            }
        });
    }

    @Test
    @DisplayName("Apple 토큰 요청에만 생성한 인증값을 넣고 Google과 Kakao 인증 방식은 유지한다")
    void customizesOnlyAppleTokenRequest() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> authorizationHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"access_token\":\"test-access\",\"token_type\":\"Bearer\",\"expires_in\":300}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            enabledRunner().run(context -> {
                assertThat(context).hasNotFailed();
                var registrations = context.getBean(ClientRegistrationRepository.class);
                var generator = mock(AppleClientSecretGenerator.class);
                when(generator.generate()).thenReturn("generated-apple-secret");
                var client = new AppleOAuthConfiguration().appleTokenResponseClient(generator);
                for (String provider : new String[]{"apple", "google", "kakao"}) {
                    var registration = ClientRegistration.withClientRegistration(registrations.findByRegistrationId(provider))
                            .tokenUri("http://127.0.0.1:" + server.getAddress().getPort() + "/token").build();
                    var request = OAuth2AuthorizationRequest.authorizationCode()
                            .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
                            .clientId(registration.getClientId())
                            .redirectUri("https://www.harudle.com/login/oauth2/code/" + provider)
                            .state("test-state").build();
                    var response = OAuth2AuthorizationResponse.success("test-code")
                            .redirectUri(request.getRedirectUri()).state(request.getState()).build();
                    client.getTokenResponse(new OAuth2AuthorizationCodeGrantRequest(registration,
                            new OAuth2AuthorizationExchange(request, response)));
                    var parameters = UriComponentsBuilder.fromUriString("https://test/?" + body.get())
                            .build().getQueryParams();
                    assertThat(parameters.getFirst("code")).isEqualTo("test-code");
                    if (provider.equals("apple")) {
                        assertThat(parameters.getFirst("client_id")).isEqualTo(registration.getClientId());
                        assertThat(parameters.get("client_secret")).containsExactly("generated-apple-secret");
                    } else if (provider.equals("google")) {
                        String expected = Base64.getEncoder().encodeToString("google-client:google-secret"
                                .getBytes(StandardCharsets.UTF_8));
                        assertThat(authorizationHeader.get()).isEqualTo("Basic " + expected);
                        assertThat(parameters).doesNotContainKey("client_secret");
                    } else {
                        assertThat(parameters.getFirst("client_id")).isEqualTo(registration.getClientId());
                        assertThat(parameters.getFirst("client_secret")).isEqualTo("kakao-secret");
                    }
                    assertThat(URLDecoder.decode(parameters.getFirst("redirect_uri"), StandardCharsets.UTF_8))
                            .isEqualTo(request.getRedirectUri());
                }
                verify(generator, times(1)).generate();
            });
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @CsvSource({"lax,true", "none,false"})
    @DisplayName("Apple을 켠 환경에서 부적절한 세션 쿠키 설정이면 시작을 거절한다")
    void rejectsIncompatibleSessionCookie(String sameSite, boolean secure) throws Exception {
        enabledRunner().withPropertyValues(
                "server.servlet.session.cookie.same-site=" + sameSite,
                "server.servlet.session.cookie.secure=" + secure
        ).run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasStackTraceContaining("Apple 로그인에는 SameSite=None과 Secure 세션 쿠키가 필요합니다."));
    }

    private ApplicationContextRunner enabledRunner() throws Exception {
        var key = new ECKeyGenerator(Curve.P_256).generate();
        Path path = directory.resolve("test-only.p8");
        String encoded = Base64.getEncoder().encodeToString(key.toECPrivateKey().getEncoded());
        Files.writeString(path, "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----\n");
        return runner.withPropertyValues(
                "app.auth.apple.enabled=true", "app.auth.apple.client-id=com.harudle.web",
                "app.auth.apple.team-id=test-team", "app.auth.apple.key-id=test-key",
                "app.auth.apple.private-key-path=" + path,
                "app.auth.apple.redirect-uri=https://www.harudle.com/login/oauth2/code/apple",
                "server.servlet.session.cookie.same-site=none",
                "server.servlet.session.cookie.secure=true"
        );
    }

}
