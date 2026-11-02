package com.harudle.common.security;

import com.harudle.auth.presentation.AuthenticationRequiredException;
import com.harudle.common.error.ErrorType;
import com.harudle.common.error.ProblemDetailResponseWriter;
import com.harudle.common.validation.CanonicalUuidParser;
import com.harudle.legal.application.TermsAcceptanceRequiredException;
import com.harudle.legal.application.TermsAcceptanceService;
import com.harudle.legal.infrastructure.LegalProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

final class TermsAcceptanceFilter extends OncePerRequestFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(TermsAcceptanceFilter.class);
    private final TermsAcceptanceService service;
    private final LegalProperties properties;
    private final ProblemDetailResponseWriter problems;

    TermsAcceptanceFilter(TermsAcceptanceService service, LegalProperties properties,
            ProblemDetailResponseWriter problems) {
        this.service = service;
        this.properties = properties;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.requireTermsAcceptance()) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String method = request.getMethod();
        if ("GET".equals(method)) {
            return path.equals("/api/v1/me") || path.equals("/api/v1/me/agreements")
                    || path.equals("/api/v1/auth/csrf") || path.equals("/api/v1/legal/documents")
                    || path.startsWith("/api/v1/legal/documents/");
        }
        return ("PUT".equals(method) && path.equals("/api/v1/me/agreements"))
                || ("POST".equals(method) && (path.equals("/api/v1/auth/refresh")
                || path.equals("/api/v1/auth/logout")));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
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
            service.requireAccepted(userId.get());
        } catch (TermsAcceptanceRequiredException exception) {
            reject(request, response, ErrorType.TERMS_ACCEPTANCE_REQUIRED);
            return;
        } catch (AuthenticationRequiredException exception) {
            reject(request, response, ErrorType.UNAUTHORIZED);
            return;
        } catch (RuntimeException exception) {
            LOGGER.error("event=terms_acceptance_check_failed exceptionType={}", exception.getClass().getSimpleName());
            reject(request, response, ErrorType.INTERNAL_SERVER_ERROR);
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, ErrorType error) throws IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        problems.write(request, response, error);
    }
}
