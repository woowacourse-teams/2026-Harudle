package com.harudle.common.error;

import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TraceIdConfiguration {

    TraceIdConfiguration() {
    }

    @Bean
    TraceIdGenerator traceIdGenerator() {
        return () -> UUID.randomUUID().toString().replace("-", "");
    }

    @Bean
    RequestTraceId requestTraceId(TraceIdGenerator traceIdGenerator) {
        return new RequestTraceId(traceIdGenerator);
    }
}
