package com.harudle.common.security;

import com.harudle.auth.application.AccountAccessPolicy;
import com.harudle.common.error.ProblemDetailResponseWriter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
public class AccountSuspensionConfiguration {
    // 서블릿 초기 등록 단계에서 JPA 서비스가 먼저 생성되지 않도록 제재 정책을 지연 주입한다.
    @Bean
    FilterRegistrationBean<AccountSuspensionFilter> accountSuspensionFilterRegistration(
            @Lazy AccountAccessPolicy accountAccess,
            ProblemDetailResponseWriter problems,
            SecurityFilterProperties securityFilters) {
        // SecurityContext는 보안 체인이 controller를 포함한 나머지 servlet chain을 호출하는 동안 유지된다.
        // 특정 SecurityFilterChain에 종속되지 않아 뒤에 추가되는 Bearer API 체인도 같은 검사를 받는다.
        var registration = new FilterRegistrationBean<>(new AccountSuspensionFilter(accountAccess, problems));
        registration.setName("accountSuspensionGuard");
        registration.setOrder(Math.addExact(securityFilters.getOrder(), 1));
        registration.addUrlPatterns("/api/v1/*");
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        return registration;
    }
}
