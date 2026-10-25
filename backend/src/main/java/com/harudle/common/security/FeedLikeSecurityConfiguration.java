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
import org.springframework.security.web.util.matcher.RequestMatcher;

/** 피드 좋아요 변경만 인증 후 CSRF를 검증한다. 기존 API 보안 체인은 변경하지 않는다. */
@Configuration(proxyBeanMethods = false)
public class FeedLikeSecurityConfiguration {

    private static final String LIKE_PATH = "/api/v1/feeds/{feedId}/like";

    @Bean
    @Order(0)
    SecurityFilterChain feedLikeSecurityFilterChain(
            HttpSecurity http,
            CookieCsrfTokenRepository csrfTokenRepository,
            AuthenticationTrustResolver authenticationTrustResolver,
            ApiAuthenticationEntryPoint authenticationEntryPoint,
            ApiAccessDeniedHandler accessDeniedHandler
    ) throws Exception {
        RequestMatcher likeMutations = new OrRequestMatcher(
                PathPatternRequestMatcher.pathPattern(HttpMethod.PUT, LIKE_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.DELETE, LIKE_PATH)
        );
        CsrfFilter csrfFilter = new CsrfFilter(csrfTokenRepository);
        csrfFilter.setRequestHandler(new CsrfTokenRequestAttributeHandler());
        csrfFilter.setAccessDeniedHandler(accessDeniedHandler);
        csrfFilter.setRequireCsrfProtectionMatcher(request -> authenticationTrustResolver.isAuthenticated(
                SecurityContextHolder.getContext().getAuthentication()
        ));

        http.securityMatcher(likeMutations)
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
