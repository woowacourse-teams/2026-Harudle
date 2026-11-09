package com.harudle.recovery;

import com.harudle.auth.application.AccessTokenService;
import com.harudle.auth.application.AuthService;
import com.harudle.auth.application.RefreshTokenService;
import com.harudle.auth.infrastructure.token.RefreshTokenCookieReader;
import com.harudle.auth.infrastructure.token.RefreshTokenCookieWriter;
import com.harudle.auth.infrastructure.token.RefreshTokenGenerator;
import com.harudle.auth.infrastructure.token.RefreshTokenHasher;
import com.harudle.common.security.AuthProperties;
import com.harudle.common.security.AuthTimeConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/** Existing session rotation only; no OAuth login, logout, or token cleanup scheduler. */
@EnableConfigurationProperties(AuthProperties.class)
@Import({AuthService.class, AccessTokenService.class, RefreshTokenService.class,
        RefreshTokenGenerator.class, RefreshTokenHasher.class, RefreshTokenCookieReader.class,
        RefreshTokenCookieWriter.class, AuthTimeConfiguration.class, ImageRecoveryAuthController.class})
public class ImageRecoveryAuthenticationConfiguration {
}
