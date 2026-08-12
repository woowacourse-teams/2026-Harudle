package com.harudle.auth.application;

import com.harudle.common.security.AccessTokenProperties;
import com.harudle.common.security.AuthProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class AccessTokenService {

    private final JwtEncoder jwtEncoder;
    private final String issuer;
    private final String audience;
    private final Duration accessTokenTtl;

    public AccessTokenService(JwtEncoder jwtEncoder, AuthProperties authProperties) {
        this.jwtEncoder = Objects.requireNonNull(jwtEncoder, "jwtEncoder는 필수입니다.");

        AccessTokenProperties accessTokenProperties = extractAccessTokenProperties(authProperties);

        this.issuer = accessTokenProperties.issuer();
        this.audience = accessTokenProperties.audience();
        this.accessTokenTtl = accessTokenProperties.ttl();
    }

    public IssuedAccessToken issue(UUID userId, Instant now) {
        Objects.requireNonNull(userId, "userId는 필수입니다.");
        Objects.requireNonNull(now, "now는 필수입니다.");

        Instant issuedAt = now.truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(accessTokenTtl);
        JwtClaimsSet claims = createClaims(userId, issuedAt, expiresAt);
        Jwt jwt = jwtEncoder.encode(JwtEncoderParameters.from(claims));

        return new IssuedAccessToken(jwt.getTokenValue(), expiresAt);
    }

    private JwtClaimsSet createClaims(UUID userId, Instant issuedAt, Instant expiresAt) {
        return JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(userId.toString())
                .audience(List.of(audience))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
    }

    private AccessTokenProperties extractAccessTokenProperties(AuthProperties authProperties) {
        Objects.requireNonNull(authProperties, "authProperties는 필수입니다.");

        AccessTokenProperties accessTokenProperties = Objects.requireNonNull(
                authProperties.accessToken(),
                "accessToken 설정은 필수입니다."
        );
        validateAccessTokenProperties(accessTokenProperties);

        return accessTokenProperties;
    }

    private void validateAccessTokenProperties(AccessTokenProperties accessTokenProperties) {
        validateRequiredText(accessTokenProperties.issuer(), "accessToken issuer");
        validateRequiredText(accessTokenProperties.audience(), "accessToken audience");

        Duration ttl = Objects.requireNonNull(
                accessTokenProperties.ttl(),
                "accessToken ttl은 필수입니다."
        );
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("accessToken ttl은 양수여야 합니다.");
        }
    }

    private void validateRequiredText(String value, String fieldName) {
        if (value != null && !value.isBlank()) {
            return;
        }

        throw new IllegalArgumentException(fieldName + "은 필수입니다.");
    }
}
