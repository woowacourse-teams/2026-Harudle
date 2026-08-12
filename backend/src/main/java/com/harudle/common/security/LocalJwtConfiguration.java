package com.harudle.common.security;

import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@Profile("local")
@Configuration(proxyBeanMethods = false)
public class LocalJwtConfiguration {

    @Bean
    JwtDecoder jwtDecoder() {
        return token -> {
            UUID userId = parseUserId(token);
            Instant now = Instant.now();

            return Jwt.withTokenValue(token)
                    .header("alg", "local")
                    .subject(userId.toString())
                    .issuedAt(now)
                    .expiresAt(now.plusSeconds(3600))
                    .build();
        };
    }

    private static UUID parseUserId(String token) {
        try {
            return UUID.fromString(token);
        } catch (IllegalArgumentException exception) {
            throw new BadJwtException("로컬 토큰은 사용자 UUID 형식이어야 합니다.");
        }
    }
}
