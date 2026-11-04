package com.harudle.common.security;

import com.harudle.common.error.ProblemDetailResponseWriter;
import com.harudle.legal.application.TermsAcceptanceService;
import com.harudle.legal.infrastructure.LegalProperties;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LegalProperties.class)
public class TermsAcceptanceConfiguration {
    @Bean
    FilterRegistrationBean<TermsAcceptanceFilter> termsAcceptanceFilterRegistration(
            @Lazy TermsAcceptanceService service, LegalProperties properties,
            ProblemDetailResponseWriter problems, SecurityFilterProperties securityFilters) {
        var registration = new FilterRegistrationBean<>(new TermsAcceptanceFilter(service, properties, problems));
        registration.setName("termsAcceptanceGuard");
        // 관리자 제재가 동의 필요 여부보다 먼저 판단되고, 개별 API의 보안 체인을 모두 포괄한다.
        registration.setOrder(Math.addExact(securityFilters.getOrder(), 2));
        registration.addUrlPatterns("/api/v1/*");
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        return registration;
    }
}
