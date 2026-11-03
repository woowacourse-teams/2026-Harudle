package com.harudle.common.security;

import com.harudle.auth.application.AccountAccessPolicy;
import com.harudle.auth.application.AccountSuspendedException;
import com.harudle.common.error.ErrorType;
import com.harudle.common.error.ProblemDetailResponseWriter;
import com.harudle.common.validation.CanonicalUuidParser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

final class AccountSuspensionFilter extends OncePerRequestFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountSuspensionFilter.class);
    private static final Set<String> UNRESTRICTED_PATHS = Set.of("/api/v1/auth/logout", "/api/v1/auth/csrf");
    private final AccountAccessPolicy accountAccess;
    private final ProblemDetailResponseWriter problems;

    AccountSuspensionFilter(AccountAccessPolicy accountAccess, ProblemDetailResponseWriter problems) {
        this.accountAccess = accountAccess;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return UNRESTRICTED_PATHS.contains(path)
                || ("GET".equals(request.getMethod()) && (path.equals("/api/v1/legal/documents")
                || path.startsWith("/api/v1/legal/documents/")));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }
        Optional<UUID> userId = CanonicalUuidParser.parse(jwt.getToken().getSubject());
        if (userId.isEmpty()) {
            reject(request, response, ErrorType.UNAUTHORIZED);
            return;
        }
        try {
            accountAccess.requireAllowedForRequest(userId.get());
        } catch (AccountSuspendedException exception) {
            reject(request, response, ErrorType.ACCOUNT_SUSPENDED);
            return;
        } catch (RuntimeException exception) {
            LOGGER.error("event=account_access_check_failed exceptionType={}", exception.getClass().getSimpleName());
            reject(request, response, ErrorType.INTERNAL_SERVER_ERROR);
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, ErrorType error)
            throws IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        problems.write(request, response, error);
    }
}
