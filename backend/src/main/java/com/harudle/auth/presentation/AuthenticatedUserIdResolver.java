package com.harudle.auth.presentation;

import com.harudle.common.validation.CanonicalUuidParser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class AuthenticatedUserIdResolver {

    private final AuthenticationTrustResolver authenticationTrustResolver;

    AuthenticatedUserIdResolver(AuthenticationTrustResolver authenticationTrustResolver) {
        this.authenticationTrustResolver = authenticationTrustResolver;
    }

    public UUID resolve(Authentication authentication) {
        return authenticatedPrincipalName(authentication)
                .flatMap(CanonicalUuidParser::parse)
                .orElseThrow(AuthenticationRequiredException::new);
    }

    private Optional<String> authenticatedPrincipalName(Authentication authentication) {
        return Optional.ofNullable(authentication)
                .filter(authenticationTrustResolver::isAuthenticated)
                .map(Authentication::getName);
    }
}
