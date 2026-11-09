package com.harudle.common.security;

import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.error.ProblemDetailResponseWriter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    @Bean
    public AuthenticationTrustResolver authenticationTrustResolver() {
        return new AuthenticationTrustResolverImpl();
    }

    @Bean
    public ProblemDetailResponseWriter problemDetailResponseWriter(
            ProblemDetailFactory problemDetailFactory,
            ObjectMapper objectMapper
    ) {
        return new ProblemDetailResponseWriter(problemDetailFactory, objectMapper);
    }

    @Bean
    public ApiAuthenticationEntryPoint apiAuthenticationEntryPoint(
            ProblemDetailResponseWriter problemDetailResponseWriter
    ) {
        return new ApiAuthenticationEntryPoint(problemDetailResponseWriter);
    }

    @Bean
    public ApiAccessDeniedHandler apiAccessDeniedHandler(
            ProblemDetailResponseWriter problemDetailResponseWriter
    ) {
        return new ApiAccessDeniedHandler(problemDetailResponseWriter);
    }

    @Bean
    public AdminAuthorizationManager adminAuthorizationManager(UserRepository userRepository) {
        return new AdminAuthorizationManager(userRepository);
    }

    @Bean
    @Order(2)
    public SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            CookieCsrfTokenRepository csrfTokenRepository,
            ApiAuthenticationEntryPoint apiAuthenticationEntryPoint,
            ApiAccessDeniedHandler apiAccessDeniedHandler,
            AdminAuthorizationManager adminAuthorizationManager
    ) throws Exception {
        http
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        // The frontend proxy does not route /actuator to this internal Docker service.
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus").permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/categories").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/legal/documents",
                                "/api/v1/legal/documents/*/*").permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/guest/session",
                                "/api/v1/guest/diaries"
                        ).permitAll()
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/guest/diaries/*",
                                "/api/v1/guest/diary-generations/*"
                        ).permitAll()
                        .requestMatchers(
                                "/api/v1/auth/apple/native/nonce",
                                "/api/v1/auth/apple/native/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/csrf",
                                "/api/v1/auth/logout",
                        "/api/v1/public/**",
                        "/error"
                        ).permitAll()
                        .requestMatchers("/api/v1/admin/**").access(adminAuthorizationManager)
                        // 캠퍼스 서버가 서버끼리 부른다. 사용자 토큰 대신 클라이언트 비밀값을 컨트롤러에서 검사한다.
                        .requestMatchers(HttpMethod.POST, "/api/v1/campus/link-codes/exchange").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/feeds", "/api/v1/feeds/*").permitAll()
                        .requestMatchers(
                                "/scalar",
                                "/scalar/**",
                                "/v3/api-docs",
                                "/v3/api-docs/**"
                        ).permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll()
                )
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .withObjectPostProcessor(new ObjectPostProcessor<CsrfFilter>() {
                            @Override
                            public <O extends CsrfFilter> O postProcess(O filter) {
                                // 쿠키 API는 Bearer 헤더가 함께 있어도 CSRF 검증을 생략하지 않는다.
                                filter.setRequireCsrfProtectionMatcher(new CookieApiCsrfProtectionMatcher());
                                filter.setAccessDeniedHandler(apiAccessDeniedHandler);
                                return filter;
                            }
                        })
                )
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(apiAuthenticationEntryPoint)
                        .accessDeniedHandler(apiAccessDeniedHandler)
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(apiAuthenticationEntryPoint)
                        .accessDeniedHandler(apiAccessDeniedHandler)
                );

        return http.build();
    }
}
