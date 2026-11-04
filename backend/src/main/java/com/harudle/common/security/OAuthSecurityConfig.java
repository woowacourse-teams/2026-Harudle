package com.harudle.common.security;

import com.harudle.auth.infrastructure.oauth.GoogleWithdrawalAuthorizationRequestResolver;
import com.harudle.auth.infrastructure.oauth.GoogleWithdrawalCallbackHandler;
import com.harudle.auth.infrastructure.oauth.OAuthLoginFailureHandler;
import com.harudle.auth.infrastructure.oauth.OAuthLoginSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration(proxyBeanMethods = false)
public class OAuthSecurityConfig {

    private final OAuthLoginSuccessHandler oAuthLoginSuccessHandler;
    private final OAuthLoginFailureHandler oAuthLoginFailureHandler;

    public OAuthSecurityConfig(
            OAuthLoginSuccessHandler oAuthLoginSuccessHandler,
            OAuthLoginFailureHandler oAuthLoginFailureHandler
    ) {
        this.oAuthLoginSuccessHandler = oAuthLoginSuccessHandler;
        this.oAuthLoginFailureHandler = oAuthLoginFailureHandler;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain oauthSecurityFilterChain(HttpSecurity http,
            GoogleWithdrawalAuthorizationRequestResolver withdrawalResolver,
            GoogleWithdrawalCallbackHandler withdrawalCallback) throws Exception {
        http
                .securityMatcher("/oauth2/**", "/login/oauth2/**")
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                )
                .csrf(csrf -> csrf.ignoringRequestMatchers(PathPatternRequestMatcher.withDefaults()
                        .matcher(HttpMethod.POST, "/login/oauth2/code/apple")))
                .oauth2Login(oauth2 -> oauth2
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(withdrawalResolver))
                        .successHandler((request, response, authentication) -> {
                            if (!withdrawalCallback.success(request, response, authentication)) {
                                oAuthLoginSuccessHandler.onAuthenticationSuccess(request, response, authentication);
                            }
                        })
                        .failureHandler((request, response, exception) -> {
                            if (!withdrawalCallback.failure(request, response)) {
                                oAuthLoginFailureHandler.onAuthenticationFailure(request, response, exception);
                            }
                        })
                );

        return http.build();
    }
}
