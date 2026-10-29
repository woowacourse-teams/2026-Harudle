package com.harudle.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

import com.harudle.auth.infrastructure.oauth.AppleNativeTokenVerifier.AppleProviderException;
import com.harudle.auth.infrastructure.oauth.AppleNativeTokenVerifier.InvalidAuthorizationCodeException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

class AppleNativeTokenVerifierTest {
    @ParameterizedTest
    @MethodSource("appleErrors")
    void distinguishesRejectedCodeFromAppleErrors(HttpStatus status, String body,
            Class<? extends RuntimeException> expected) {
        var builder = RestClient.builder().baseUrl("https://appleid.apple.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://appleid.apple.com/auth/token"))
                .andRespond(MockRestResponseCreators.withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body));
        var nativeToken = Jwt.withTokenValue("native").header("alg", "RS256")
                .issuer("https://appleid.apple.com").subject("user").audience(List.of("our-app"))
                .expiresAt(Instant.now().plusSeconds(60)).claim("nonce", "nonce").build();
        var generator = mock(AppleClientSecretGenerator.class);
        when(generator.generate("our-app")).thenReturn("secret");
        var verifier = new AppleNativeTokenVerifier(generator, "our-app", ignored -> nativeToken, builder.build());

        assertThatThrownBy(() -> verifier.verify("code", "native", "nonce")).isInstanceOf(expected);
        server.verify();
    }

    private static Stream<Arguments> appleErrors() {
        return Stream.of(
                Arguments.of(HttpStatus.BAD_REQUEST, "{\"error\":\"invalid_grant\"}", InvalidAuthorizationCodeException.class),
                Arguments.of(HttpStatus.BAD_REQUEST, "{\"error\":\"invalid_client\"}", AppleProviderException.class),
                Arguments.of(HttpStatus.BAD_REQUEST, "{\"error\":\"invalid_request\"}", AppleProviderException.class),
                Arguments.of(HttpStatus.BAD_REQUEST, "not json", AppleProviderException.class),
                Arguments.of(HttpStatus.INTERNAL_SERVER_ERROR, "{\"error\":\"invalid_grant\"}", AppleProviderException.class)
        );
    }

    @Test
    void distinguishesInvalidJwtFromUnavailableAppleKeys() throws Exception {
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("https://appleid.apple.com/auth/keys"))
                .andRespond(MockRestResponseCreators.withServerError());
        var decoder = NimbusJwtDecoder.withJwkSetUri("https://appleid.apple.com/auth/keys")
                .restOperations(rest).build();
        var verifier = new AppleNativeTokenVerifier(mock(AppleClientSecretGenerator.class), "our-app",
                decoder, mock(RestClient.class));

        assertThatThrownBy(() -> verifier.verify("code", "malformed", "nonce"))
                .isInstanceOf(IllegalArgumentException.class);

        var key = new RSAKeyGenerator(2048).keyID("key").generate();
        var signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("key").build(),
                new JWTClaimsSet.Builder().subject("user").build());
        signed.sign(new RSASSASigner(key));
        assertThatThrownBy(() -> verifier.verify("code", signed.serialize(), "nonce"))
                .isInstanceOf(AppleProviderException.class);
        server.verify();
    }

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
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://appleid.apple.com/auth/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("client_id=our-app")))
                .andRespond(MockRestResponseCreators.withSuccess("{\"id_token\":\"exchanged\"}", MediaType.APPLICATION_JSON));
        var nativeToken = Jwt.withTokenValue("native").header("alg", "RS256")
                .issuer("https://appleid.apple.com").subject("user")
                .audience(List.of("our-app")).expiresAt(Instant.now().plusSeconds(60))
                .claim("nonce", "nonce").build();
        var exchanged = Jwt.withTokenValue("exchanged").header("alg", "RS256")
                .issuer("https://appleid.apple.com").subject("user")
                .audience(List.of("our-app")).expiresAt(Instant.now().plusSeconds(60)).build();
        var generator = mock(AppleClientSecretGenerator.class);
        when(generator.generate("our-app")).thenReturn("secret");
        var verifier = new AppleNativeTokenVerifier(generator, "our-app",
                token -> token.equals("native") ? nativeToken : exchanged, builder.build());
        Jwt verified = verifier.verify("code", "native", "nonce");
        assertThat(verified.getSubject()).isEqualTo("user");
        assertThat(verified.getTokenValue()).isEqualTo("exchanged");
        server.verify();
    }

    @Test
    void rejectsTokensForDifferentAppleUsers() {
        var builder = RestClient.builder().baseUrl("https://appleid.apple.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://appleid.apple.com/auth/token"))
                .andRespond(MockRestResponseCreators.withSuccess("{\"id_token\":\"exchanged\"}", MediaType.APPLICATION_JSON));
        var nativeToken = Jwt.withTokenValue("native").header("alg", "RS256")
                .issuer("https://appleid.apple.com").subject("user")
                .audience(List.of("our-app")).expiresAt(Instant.now().plusSeconds(60))
                .claim("nonce", "nonce").build();
        var exchanged = Jwt.withTokenValue("exchanged").header("alg", "RS256")
                .issuer("https://appleid.apple.com").subject("different-user")
                .audience(List.of("our-app")).expiresAt(Instant.now().plusSeconds(60)).build();
        var generator = mock(AppleClientSecretGenerator.class);
        when(generator.generate("our-app")).thenReturn("secret");
        var verifier = new AppleNativeTokenVerifier(generator, "our-app",
                token -> token.equals("native") ? nativeToken : exchanged, builder.build());

        assertThatThrownBy(() -> verifier.verify("code", "native", "nonce"))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test
    void treatsMissingExchangedIdTokenAsAppleFailure() {
        var builder = RestClient.builder().baseUrl("https://appleid.apple.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://appleid.apple.com/auth/token"))
                .andRespond(MockRestResponseCreators.withSuccess("{}", MediaType.APPLICATION_JSON));
        var nativeToken = Jwt.withTokenValue("native").header("alg", "RS256")
                .issuer("https://appleid.apple.com").subject("user").audience(List.of("our-app"))
                .expiresAt(Instant.now().plusSeconds(60)).claim("nonce", "nonce").build();
        var generator = mock(AppleClientSecretGenerator.class);
        when(generator.generate("our-app")).thenReturn("secret");
        var verifier = new AppleNativeTokenVerifier(generator, "our-app", ignored -> nativeToken, builder.build());

        assertThatThrownBy(() -> verifier.verify("code", "native", "nonce"))
                .isInstanceOf(AppleProviderException.class);
        server.verify();
    }
}
