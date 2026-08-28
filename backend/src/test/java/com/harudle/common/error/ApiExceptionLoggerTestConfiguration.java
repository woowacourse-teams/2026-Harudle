package com.harudle.common.error;

import static org.mockito.Mockito.mock;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class ApiExceptionLoggerTestConfiguration {

    @Bean
    ApiExceptionLogger apiExceptionLogger() {
        return mock(ApiExceptionLogger.class);
    }
}
