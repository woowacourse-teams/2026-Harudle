package com.harudle.auth.presentation;

import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class AuthenticatedUserIdResolver {

    public UUID resolve(Authentication authentication) {
        validateAuthentication(authentication);
        String principalName = authentication.getName();
        if (principalName == null) {
            throw new AuthenticationRequiredException();
        }
        try {
            return UUID.fromString(principalName);
        } catch (IllegalArgumentException exception) {
            throw new AuthenticationRequiredException();
        }
    }

    private static void validateAuthentication(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new AuthenticationRequiredException();
        }
    }
}
