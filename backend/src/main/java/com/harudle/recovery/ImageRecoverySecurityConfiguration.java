package com.harudle.recovery;

import com.harudle.auth.application.AccountAccessPolicy;
import com.harudle.auth.application.AccountSuspendedException;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.presentation.AuthenticationRequiredException;
import com.harudle.common.error.ApiExceptionLogger;
import com.harudle.common.error.ErrorType;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.error.ProblemDetailResponseWriter;
import com.harudle.common.security.AdminAuthorizationManager;
import com.harudle.common.security.ApiAccessDeniedHandler;
import com.harudle.common.security.ApiAuthenticationEntryPoint;
import com.harudle.common.security.AuthProperties;
import com.harudle.legal.application.TermsAcceptanceRequiredException;
import com.harudle.legal.application.TermsAcceptanceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfFilter;
import tools.jackson.databind.ObjectMapper;

@EnableConfigurationProperties(AuthProperties.class)
class ImageRecoverySecurityConfiguration {
    @Bean
    ProblemDetailResponseWriter recoveryProblems(ProblemDetailFactory factory, ObjectMapper mapper) {
        return new ProblemDetailResponseWriter(factory, mapper);
    }

    @Bean
    SecurityFilterChain recoverySecurity(HttpSecurity http, CookieCsrfTokenRepository tokens,
            ProblemDetailResponseWriter problems, UserRepository users, AccountAccessPolicy accountAccess,
            TermsAcceptanceService terms, ApiExceptionLogger exceptionLogger)
            throws Exception {
        var entryPoint = new ApiAuthenticationEntryPoint(problems);
        var denied = new ApiAccessDeniedHandler(problems) {
            @Override
            public void handle(HttpServletRequest request, HttpServletResponse response,
                    AccessDeniedException exception) throws IOException {
                if (exception instanceof RecoveryPolicyDeniedException policy) {
                    if (policy.error == ErrorType.INTERNAL_SERVER_ERROR) {
                        exceptionLogger.error(policy.error, policy, request);
                    }
                    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                    problems.write(request, response, policy.error);
                    return;
                }
                super.handle(request, response, exception);
            }
        };
        var admins = new AdminAuthorizationManager(users);
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/admin/generations/{generationId}/restore-image",
                                "/api/v1/admin/generations/restore-image/upload",
                                "/api/v1/admin/generations/restore-image/r2")
                        .access((authentication, context) -> {
                            try {
                                var result = admins.authorize(authentication, context);
                                if (result == null || !result.isGranted()) return new AuthorizationDecision(false);
                                UUID userId = UUID.fromString(authentication.get().getName());
                                accountAccess.requireAllowedForRequest(userId);
                                terms.requireAccepted(userId);
                                return new AuthorizationDecision(true);
                            } catch (AccountSuspendedException exception) {
                                throw new RecoveryPolicyDeniedException(ErrorType.ACCOUNT_SUSPENDED);
                            } catch (TermsAcceptanceRequiredException exception) {
                                throw new RecoveryPolicyDeniedException(ErrorType.TERMS_ACCEPTANCE_REQUIRED);
                            } catch (AuthenticationRequiredException exception) {
                                throw new RecoveryPolicyDeniedException(ErrorType.UNAUTHORIZED);
                            } catch (RuntimeException exception) {
                                throw new RecoveryPolicyDeniedException(ErrorType.INTERNAL_SERVER_ERROR, exception);
                            }
                        })
                        .anyRequest().denyAll())
                .csrf(csrf -> csrf.csrfTokenRepository(tokens)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .withObjectPostProcessor(new ObjectPostProcessor<CsrfFilter>() {
                            @Override
                            public <O extends CsrfFilter> O postProcess(O filter) {
                                filter.setRequireCsrfProtectionMatcher(CsrfFilter.DEFAULT_CSRF_MATCHER);
                                filter.setAccessDeniedHandler(denied);
                                return filter;
                            }
                        }))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(denied))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(entryPoint).accessDeniedHandler(denied));
        return http.build();
    }

    private static final class RecoveryPolicyDeniedException extends AccessDeniedException {
        private final ErrorType error;

        private RecoveryPolicyDeniedException(ErrorType error) {
            this(error, null);
        }

        private RecoveryPolicyDeniedException(ErrorType error, Throwable cause) {
            super("Image recovery authorization rejected.", cause);
            this.error = error;
        }
    }
}
