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
import org.springframework.security.web.util.matcher.OrRequestMatcher;

/** 인앱 알림 경로만 보호하고 읽음 변경은 인증 이후 CSRF를 검증한다. */
@Configuration(proxyBeanMethods = false)
public class NotificationSecurityConfiguration {
    @Bean
    @Order(-2)
    SecurityFilterChain notificationSecurityFilterChain(HttpSecurity http,
            CookieCsrfTokenRepository csrfTokenRepository, AuthenticationTrustResolver trustResolver,
            ApiAuthenticationEntryPoint authenticationEntryPoint, ApiAccessDeniedHandler accessDeniedHandler) throws Exception {
        var list = PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/v1/notifications");
        var update = PathPatternRequestMatcher.pathPattern(HttpMethod.PATCH, "/api/v1/notifications/{notificationId}");
        CsrfFilter csrfFilter = new CsrfFilter(csrfTokenRepository);
        csrfFilter.setRequestHandler(new CsrfTokenRequestAttributeHandler());
        csrfFilter.setAccessDeniedHandler(accessDeniedHandler);
        csrfFilter.setRequireCsrfProtectionMatcher(request -> update.matches(request)
                && trustResolver.isAuthenticated(SecurityContextHolder.getContext().getAuthentication()));

        http.securityMatcher(new OrRequestMatcher(list, update))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .csrf(AbstractHttpConfigurer::disable)
                .exceptionHandling(exception -> exception.authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(authenticationEntryPoint).accessDeniedHandler(accessDeniedHandler))
                .addFilterAfter(csrfFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
