package com.harudle.common.security;

import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.common.validation.CanonicalUuidParser;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

public class AdminAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final UserRepository userRepository;

    public AdminAuthorizationManager(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public AuthorizationResult authorize(
            Supplier<? extends Authentication> authenticationSupplier,
            RequestAuthorizationContext context
    ) {
        boolean granted = authenticatedUser(authenticationSupplier.get())
                .flatMap(userRepository::findById)
                .filter(user -> !user.isDeleted())
                .map(User::isAdmin)
                .orElse(false);
        return new AuthorizationDecision(granted);
    }

    private Optional<UUID> authenticatedUser(Authentication authentication) {
        return Optional.ofNullable(authentication)
                .filter(Authentication::isAuthenticated)
                .map(Authentication::getName)
                .flatMap(CanonicalUuidParser::parse);
    }
}
