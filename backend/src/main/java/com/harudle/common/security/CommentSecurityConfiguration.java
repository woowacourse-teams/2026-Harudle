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

/** 댓글 목록은 공개하고 댓글 작성·삭제만 인증 이후 CSRF를 검증한다. */
@Configuration(proxyBeanMethods = false)
public class CommentSecurityConfiguration {

    @Bean
    @Order(-1)
    SecurityFilterChain commentSecurityFilterChain(HttpSecurity http,
            CookieCsrfTokenRepository csrfTokenRepository, AuthenticationTrustResolver trustResolver,
            ApiAuthenticationEntryPoint authenticationEntryPoint, ApiAccessDeniedHandler accessDeniedHandler) throws Exception {
        var list = PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/v1/feeds/{feedId}/comments");
        var create = PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/feeds/{feedId}/comments");
        var delete = PathPatternRequestMatcher.pathPattern(HttpMethod.DELETE, "/api/v1/comments/{commentId}");
        var mutations = new OrRequestMatcher(create, delete);
        CsrfFilter csrfFilter = new CsrfFilter(csrfTokenRepository);
        csrfFilter.setRequestHandler(new CsrfTokenRequestAttributeHandler());
        csrfFilter.setAccessDeniedHandler(accessDeniedHandler);
        csrfFilter.setRequireCsrfProtectionMatcher(request -> mutations.matches(request)
                && trustResolver.isAuthenticated(SecurityContextHolder.getContext().getAuthentication()));

        http.securityMatcher(new OrRequestMatcher(list, create, delete))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth.requestMatchers(list).permitAll().anyRequest().authenticated())
                .csrf(AbstractHttpConfigurer::disable)
                .exceptionHandling(exception -> exception.authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(authenticationEntryPoint).accessDeniedHandler(accessDeniedHandler))
                .addFilterAfter(csrfFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
