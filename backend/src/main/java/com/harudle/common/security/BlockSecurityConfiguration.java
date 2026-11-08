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

/** 차단 API는 활성 Bearer 인증을 먼저 검증하고 변경 요청에 CSRF를 요구한다. */
@Configuration(proxyBeanMethods = false)
public class BlockSecurityConfiguration {
    @Bean
    @Order(-2)
    SecurityFilterChain blockSecurityFilterChain(HttpSecurity http, CookieCsrfTokenRepository repository,
            AuthenticationTrustResolver trustResolver, ApiAuthenticationEntryPoint entryPoint,
            ApiAccessDeniedHandler deniedHandler) throws Exception {
        var list = PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/v1/blocks");
        var put = PathPatternRequestMatcher.pathPattern(HttpMethod.PUT, "/api/v1/blocks/{userId}");
        var delete = PathPatternRequestMatcher.pathPattern(HttpMethod.DELETE, "/api/v1/blocks/{userId}");
        var mutations = new OrRequestMatcher(put, delete);
        CsrfFilter csrf = new CsrfFilter(repository);
        csrf.setRequestHandler(new CsrfTokenRequestAttributeHandler());
        csrf.setAccessDeniedHandler(deniedHandler);
        csrf.setRequireCsrfProtectionMatcher(request -> mutations.matches(request)
                && trustResolver.isAuthenticated(SecurityContextHolder.getContext().getAuthentication()));
        http.securityMatcher(new OrRequestMatcher(list, put, delete))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .csrf(AbstractHttpConfigurer::disable)
                .exceptionHandling(exception -> exception.authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler))
                .addFilterAfter(csrf, BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
