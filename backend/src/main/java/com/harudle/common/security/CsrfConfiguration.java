package com.harudle.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration(proxyBeanMethods = false)
public class CsrfConfiguration {

    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final String CSRF_COOKIE_PATH = "/api/v1/auth";
    private static final String CSRF_SAME_SITE = "Lax";

    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName(CSRF_COOKIE_NAME);
        repository.setCookiePath(CSRF_COOKIE_PATH);
        repository.setCookieCustomizer(builder -> builder.sameSite(CSRF_SAME_SITE));

        return repository;
    }
}
