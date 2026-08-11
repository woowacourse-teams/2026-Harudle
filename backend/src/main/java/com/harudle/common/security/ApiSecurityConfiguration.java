package com.harudle.common.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

@Configuration(proxyBeanMethods = false)
@Import(ApiProblemResponseWriter.class)
public class ApiSecurityConfiguration {

    private static final String BEARER_TOKEN_ENTRY_POINT = "bearerTokenEntryPoint";
    private static final String BEARER_TOKEN_ACCESS_DENIED_HANDLER = "bearerTokenAccessDeniedHandler";
    private static final int API_SECURITY_CHAIN_ORDER = 1;

    ApiSecurityConfiguration() {
    }

    @Bean
    AuthenticationTrustResolver authenticationTrustResolver() {
        return new AuthenticationTrustResolverImpl();
    }

    @Bean
    AuthenticationEntryPoint bearerTokenEntryPoint() {
        return new BearerTokenAuthenticationEntryPoint();
    }

    @Bean
    AccessDeniedHandler bearerTokenAccessDeniedHandler() {
        return new BearerTokenAccessDeniedHandler();
    }

    @Bean
    ApiAuthenticationEntryPoint apiAuthenticationEntryPoint(
            @Qualifier(BEARER_TOKEN_ENTRY_POINT) AuthenticationEntryPoint bearerTokenEntryPoint,
            ApiProblemResponseWriter problemResponseWriter
    ) {
        return new ApiAuthenticationEntryPoint(bearerTokenEntryPoint, problemResponseWriter);
    }

    @Bean
    ApiAccessDeniedHandler apiAccessDeniedHandler(
            @Qualifier(BEARER_TOKEN_ACCESS_DENIED_HANDLER) AccessDeniedHandler bearerTokenAccessDeniedHandler,
            ApiProblemResponseWriter problemResponseWriter
    ) {
        return new ApiAccessDeniedHandler(bearerTokenAccessDeniedHandler, problemResponseWriter);
    }

    @Bean
    @Order(API_SECURITY_CHAIN_ORDER)
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            ApiAuthenticationEntryPoint authenticationEntryPoint,
            ApiAccessDeniedHandler accessDeniedHandler,
            JwtDecoder jwtDecoder
    ) throws Exception {
        http.securityMatcher("/api/v1/**")
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/v1/public/**",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout"
                        )
                        .permitAll()
                        .anyRequest()
                        .authenticated()
                )
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                );
        configureResourceServer(http, jwtDecoder, authenticationEntryPoint);
        return http.build();
    }

    private static void configureResourceServer(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            ApiAuthenticationEntryPoint authenticationEntryPoint
    ) {
        http.oauth2ResourceServer(resourceServer -> resourceServer
                .jwt(jwt -> jwt.decoder(jwtDecoder))
                .authenticationEntryPoint(authenticationEntryPoint)
        );
    }
}
