package com.harudle.common.security;

import java.net.URI;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        List<String> frontendOrigins,
        URI successRedirect,
        URI failureRedirect,
        @NestedConfigurationProperty AccessTokenProperties accessToken,
        @NestedConfigurationProperty RefreshTokenProperties refreshToken
) {
}
