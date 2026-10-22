package com.harudle.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/** Existing comment/like policy: authenticate the Bearer token before checking the mutation's CSRF token. */
@Configuration(proxyBeanMethods = false)
public class ReportSecurityConfiguration {

    @Bean
    @Order(-1)
    SecurityFilterChain reportSecurityFilterChain(HttpSecurity http, CookieCsrfTokenRepository repository,
            AuthenticationTrustResolver trustResolver, ApiAuthenticationEntryPoint authenticationEntryPoint,
            ApiAccessDeniedHandler accessDeniedHandler) throws Exception {
        var create = PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/reports");
        CsrfFilter csrf = new CsrfFilter(repository);
        csrf.setRequestHandler(new CsrfTokenRequestAttributeHandler());
        csrf.setAccessDeniedHandler(accessDeniedHandler);
        csrf.setRequireCsrfProtectionMatcher(request -> create.matches(request)
                && trustResolver.isAuthenticated(SecurityContextHolder.getContext().getAuthentication()));
        http.securityMatcher(create)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .csrf(AbstractHttpConfigurer::disable)
                .exceptionHandling(exception -> exception.authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(authenticationEntryPoint).accessDeniedHandler(accessDeniedHandler))
                .addFilterAfter(csrf, BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
