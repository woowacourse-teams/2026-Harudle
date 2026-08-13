package com.harudle.common.security;

import java.util.Base64;
import java.util.List;
import java.util.Objects;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration(proxyBeanMethods = false)
public class JwtTokenConfiguration {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MINIMUM_SECRET_LENGTH = 32;

    @Bean
    public SecretKey accessTokenSecretKey(AuthProperties authProperties) {
        AccessTokenProperties accessTokenProperties = extractAccessTokenProperties(authProperties);
        byte[] secret = decodeSecret(accessTokenProperties.secretBase64());
        validateSecretLength(secret);

        return new SecretKeySpec(secret, HMAC_ALGORITHM);
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey accessTokenSecretKey) {
        Objects.requireNonNull(accessTokenSecretKey, "accessTokenSecretKey는 필수입니다.");

        return NimbusJwtEncoder.withSecretKey(accessTokenSecretKey)
                .algorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey accessTokenSecretKey, AuthProperties authProperties) {
        AccessTokenProperties accessTokenProperties = extractAccessTokenProperties(authProperties);
        Objects.requireNonNull(accessTokenSecretKey, "accessTokenSecretKey는 필수입니다.");

        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withSecretKey(accessTokenSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        jwtDecoder.setJwtValidator(
                JwtValidators.createDefaultWithValidators(
                        List.of(
                                new JwtIssuerValidator(accessTokenProperties.issuer()),
                                new JwtAudienceValidator(accessTokenProperties.audience())
                        )
                )
        );

        return jwtDecoder;
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
        validateRequiredText(accessTokenProperties.secretBase64(), "accessToken secretBase64");

        Objects.requireNonNull(accessTokenProperties.ttl(), "accessToken ttl은 필수입니다.");

        if (accessTokenProperties.ttl().isZero() || accessTokenProperties.ttl().isNegative()) {
            throw new IllegalArgumentException("accessToken ttl은 양수여야 합니다.");
        }
    }

    private void validateRequiredText(String value, String fieldName) {
        if (value != null && !value.isBlank()) {
            return;
        }

        throw new IllegalArgumentException(fieldName + "은 필수입니다.");
    }

    private byte[] decodeSecret(String secretBase64) {
        try {
            return Base64.getDecoder().decode(secretBase64);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "accessToken secretBase64는 Base64 형식이어야 합니다.",
                    exception
            );
        }
    }

    private void validateSecretLength(byte[] secret) {
        if (secret.length >= MINIMUM_SECRET_LENGTH) {
            return;
        }

        throw new IllegalArgumentException(
                "accessToken secretBase64는 32바이트 이상이어야 합니다."
        );
    }
}
