package com.harudle.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtTokenConfigurationTest {

    private static final String ISSUER = "harudle";
    private static final String AUDIENCE = "harudle-api";
    private static final String SECRET_BASE64 = createSecretBase64(
            "01234567890123456789012345678901"
    );
    private static final Instant ISSUED_AT = Instant.parse("2026-08-12T10:00:00Z");

    private final JwtTokenConfiguration configuration = new JwtTokenConfiguration();

    @Test
    @DisplayName("32바이트 이상의 Base64 secret으로 HS256 키를 생성한다")
    void createsHs256SecretKey() {
        AuthProperties authProperties = createAuthProperties(
                ISSUER,
                AUDIENCE,
                SECRET_BASE64,
                Duration.ofMinutes(30)
        );

        SecretKey secretKey = configuration.accessTokenSecretKey(authProperties);

        assertThat(secretKey.getAlgorithm()).isEqualTo("HmacSHA256");
        assertThat(secretKey.getEncoded()).hasSize(32);
    }

    @Test
    @DisplayName("짧은 secret은 Access Token 키로 사용할 수 없다")
    void rejectsShortSecret() {
        AuthProperties authProperties = createAuthProperties(
                ISSUER,
                AUDIENCE,
                createSecretBase64("short-secret"),
                Duration.ofMinutes(30)
        );

        assertThatThrownBy(() -> configuration.accessTokenSecretKey(authProperties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32바이트");
    }

    @Test
    @DisplayName("Base64 형식이 아닌 secret은 Access Token 키로 사용할 수 없다")
    void rejectsInvalidBase64Secret() {
        AuthProperties authProperties = createAuthProperties(
                ISSUER,
                AUDIENCE,
                "not-base64!",
                Duration.ofMinutes(30)
        );

        assertThatThrownBy(() -> configuration.accessTokenSecretKey(authProperties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Base64");
    }

    @Test
    @DisplayName("올바르지 않은 서명의 Access Token을 거부한다")
    void rejectsTokenWithInvalidSignature() {
        AuthProperties authProperties = createAuthProperties(
                ISSUER,
                AUDIENCE,
                SECRET_BASE64,
                Duration.ofMinutes(30)
        );
        JwtDecoder jwtDecoder = createDecoder(authProperties);
        JwtEncoder wrongEncoder = configuration.jwtEncoder(
                new SecretKeySpec(
                        "01234567890123456789012345678902".getBytes(StandardCharsets.UTF_8),
                        "HmacSHA256"
                )
        );
        String token = encode(wrongEncoder, ISSUER, AUDIENCE, ISSUED_AT, ISSUED_AT.plusSeconds(60));

        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("올바르지 않은 issuer의 Access Token을 거부한다")
    void rejectsTokenWithInvalidIssuer() {
        AuthProperties authProperties = createAuthProperties(
                ISSUER,
                AUDIENCE,
                SECRET_BASE64,
                Duration.ofMinutes(30)
        );
        JwtDecoder jwtDecoder = createDecoder(authProperties);
        String token = encodeWithConfiguredKey(
                authProperties,
                "another-issuer",
                AUDIENCE,
                ISSUED_AT,
                ISSUED_AT.plusSeconds(60)
        );

        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("올바르지 않은 audience의 Access Token을 거부한다")
    void rejectsTokenWithInvalidAudience() {
        AuthProperties authProperties = createAuthProperties(
                ISSUER,
                AUDIENCE,
                SECRET_BASE64,
                Duration.ofMinutes(30)
        );
        JwtDecoder jwtDecoder = createDecoder(authProperties);
        String token = encodeWithConfiguredKey(
                authProperties,
                ISSUER,
                "another-api",
                ISSUED_AT,
                ISSUED_AT.plusSeconds(60)
        );

        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("만료된 Access Token을 거부한다")
    void rejectsExpiredToken() {
        AuthProperties authProperties = createAuthProperties(
                ISSUER,
                AUDIENCE,
                SECRET_BASE64,
                Duration.ofMinutes(30)
        );
        JwtDecoder jwtDecoder = createDecoder(authProperties);
        Instant now = Instant.now();
        String token = encodeWithConfiguredKey(
                authProperties,
                ISSUER,
                AUDIENCE,
                now.minusSeconds(180),
                now.minusSeconds(120)
        );

        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .isInstanceOf(JwtException.class);
    }

    private JwtDecoder createDecoder(AuthProperties authProperties) {
        return configuration.jwtDecoder(
                configuration.accessTokenSecretKey(authProperties),
                authProperties
        );
    }

    private String encodeWithConfiguredKey(
            AuthProperties authProperties,
            String issuer,
            String audience,
            Instant issuedAt,
            Instant expiresAt
    ) {
        JwtEncoder jwtEncoder = configuration.jwtEncoder(
                configuration.accessTokenSecretKey(authProperties)
        );
        return encode(jwtEncoder, issuer, audience, issuedAt, expiresAt);
    }

    private String encode(
            JwtEncoder jwtEncoder,
            String issuer,
            String audience,
            Instant issuedAt,
            Instant expiresAt
    ) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("user-id")
                .audience(List.of(audience))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    private static AuthProperties createAuthProperties(
            String issuer,
            String audience,
            String secretBase64,
            Duration accessTokenTtl
    ) {
        return new AuthProperties(
                "http://localhost:5173",
                URI.create("http://localhost:5173/auth/callback"),
                URI.create("http://localhost:5173/auth/callback?error=oauth_failed"),
                new AccessTokenProperties(
                        issuer,
                        audience,
                        secretBase64,
                        accessTokenTtl
                ),
                new RefreshTokenProperties(
                        "refresh_token",
                        "/api/v1/auth",
                        false,
                        "Lax",
                        Duration.ofDays(14)
                )
        );
    }

    private static String createSecretBase64(String secret) {
        return Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8));
    }
}
