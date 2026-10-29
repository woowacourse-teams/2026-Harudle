package com.harudle.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.RestClient;

class AppleNativeTokenVerifierTest {
    @Test
    void rejectsWrongAudienceAndNonceBeforeExchangingCode() {
        RestClient client = mock(RestClient.class);
        var jwt = Jwt.withTokenValue("token").header("alg", "RS256")
                .issuer("https://appleid.apple.com").subject("user")
                .audience(List.of("wrong-app")).expiresAt(Instant.now().plusSeconds(60))
                .claim("nonce", "nonce").build();
        var verifier = new AppleNativeTokenVerifier(mock(AppleClientSecretGenerator.class), "our-app",
                ignored -> jwt, client);
        assertThatThrownBy(() -> verifier.verify("code", "token", "nonce"))
                .isInstanceOf(IllegalArgumentException.class);
        var validAudience = Jwt.withTokenValue("token").header("alg", "RS256")
                .issuer("https://appleid.apple.com").subject("user")
                .audience(List.of("our-app")).expiresAt(Instant.now().plusSeconds(60))
                .claim("nonce", "other").build();
        var nonceVerifier = new AppleNativeTokenVerifier(mock(AppleClientSecretGenerator.class), "our-app",
                ignored -> validAudience, client);
        assertThatThrownBy(() -> nonceVerifier.verify("code", "token", "nonce"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }
    @Test
    void exchangesCodeAndUsesVerifiedAppleUser() {
        var builder = RestClient.builder().baseUrl("https://appleid.apple.com");
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://appleid.apple.com/auth/token"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(org.springframework.http.HttpMethod.POST))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("client_id=our-app")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withSuccess("{\"id_token\":\"exchanged\"}", org.springframework.http.MediaType.APPLICATION_JSON));
        var nativeToken = Jwt.withTokenValue("native").header("alg", "RS256")
                .issuer("https://appleid.apple.com").subject("user")
                .audience(List.of("our-app")).expiresAt(Instant.now().plusSeconds(60))
                .claim("nonce", "nonce").build();
        var exchanged = Jwt.withTokenValue("exchanged").header("alg", "RS256")
                .issuer("https://appleid.apple.com").subject("user")
                .audience(List.of("our-app")).expiresAt(Instant.now().plusSeconds(60)).build();
        var generator = mock(AppleClientSecretGenerator.class);
        org.mockito.Mockito.when(generator.generate("our-app")).thenReturn("secret");
        var verifier = new AppleNativeTokenVerifier(generator, "our-app",
                token -> token.equals("native") ? nativeToken : exchanged, builder.build());
        org.assertj.core.api.Assertions.assertThat(verifier.verify("code", "native", "nonce").getSubject())
                .isEqualTo("user");
        server.verify();
    }
}
