package com.harudle.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.common.security.AccessTokenProperties;
import com.harudle.common.security.AuthProperties;
import com.harudle.common.security.JwtTokenConfiguration;
import com.harudle.common.security.RefreshTokenProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

class AccessTokenServiceTest {

    private static final String ISSUER = "harudle";
    private static final String AUDIENCE = "harudle-api";
    private static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(30);
    private static final Instant ISSUED_AT = Instant.parse("2026-08-12T10:00:00Z");
    private static final String SECRET_BASE64 = createSecretBase64();

    private final AuthProperties authProperties = createAuthProperties(
            ISSUER,
            AUDIENCE,
            SECRET_BASE64,
            ACCESS_TOKEN_TTL
    );
    private final JwtTokenConfiguration jwtTokenConfiguration = new JwtTokenConfiguration();
    private final JwtEncoder jwtEncoder = jwtTokenConfiguration.jwtEncoder(
            jwtTokenConfiguration.accessTokenSecretKey(authProperties)
    );
    private final JwtDecoder jwtDecoder = jwtTokenConfiguration.jwtDecoder(
            jwtTokenConfiguration.accessTokenSecretKey(authProperties),
            authProperties
    );
    private final AccessTokenService accessTokenService = new AccessTokenService(
            jwtEncoder,
            authProperties
    );

    @Test
    @DisplayName("사용자 식별자와 인증 정보를 담은 Access Token을 발급한다")
    void issuesAccessTokenWithAuthenticationClaims() {
        UUID userId = UUID.randomUUID();

        IssuedAccessToken issuedToken = accessTokenService.issue(userId, ISSUED_AT);
        Jwt jwt = jwtDecoder.decode(issuedToken.accessToken());

        assertThat(jwt.getTokenValue()).isEqualTo(issuedToken.accessToken());
        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(ISSUER);
        assertThat(jwt.getAudience()).containsExactly(AUDIENCE);
        assertThat(jwt.getIssuedAt()).isEqualTo(ISSUED_AT);
        assertThat(jwt.getExpiresAt()).isEqualTo(ISSUED_AT.plus(ACCESS_TOKEN_TTL));
        assertThat(issuedToken.expiresAt()).isEqualTo(ISSUED_AT.plus(ACCESS_TOKEN_TTL));
        assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
    }

    @Test
    @DisplayName("JWT 초 정밀도에 맞춰 발급 시각을 정리한다")
    void truncatesIssuedAtToSeconds() {
        UUID userId = UUID.randomUUID();
        Instant preciseTime = Instant.parse("2026-08-12T10:00:00.123456Z");
        Instant normalizedTime = Instant.parse("2026-08-12T10:00:00Z");

        IssuedAccessToken issuedToken = accessTokenService.issue(userId, preciseTime);
        Jwt jwt = jwtDecoder.decode(issuedToken.accessToken());

        assertThat(jwt.getIssuedAt()).isEqualTo(normalizedTime);
        assertThat(jwt.getExpiresAt()).isEqualTo(normalizedTime.plus(ACCESS_TOKEN_TTL));
        assertThat(issuedToken.expiresAt()).isEqualTo(normalizedTime.plus(ACCESS_TOKEN_TTL));
    }

    @Test
    @DisplayName("사용자 식별자가 없으면 Access Token을 발급할 수 없다")
    void rejectsNullUserId() {
        assertThatThrownBy(() -> accessTokenService.issue(null, ISSUED_AT))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("발급 시각이 없으면 Access Token을 발급할 수 없다")
    void rejectsNullIssuedAt() {
        assertThatThrownBy(() -> accessTokenService.issue(UUID.randomUUID(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Access Token TTL이 양수가 아니면 서비스를 생성할 수 없다")
    void rejectsNonPositiveTtl() {
        AuthProperties properties = createAuthProperties(
                ISSUER,
                AUDIENCE,
                SECRET_BASE64,
                Duration.ZERO
        );

        assertThatThrownBy(() -> new AccessTokenService(jwtEncoder, properties))
                .isInstanceOf(IllegalArgumentException.class);
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

    private static String createSecretBase64() {
        byte[] secret = "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8);
        return Base64.getEncoder().encodeToString(secret);
    }
}
