package com.harudle.common.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ApiResponseMetricsConfiguration {

    @Bean
    ApiResponseObservationHandler apiResponseObservationHandler(
            MeterRegistry registry,
            @Value("${harudle.monitoring.api-response.node:local}") String node
    ) {
        return new ApiResponseObservationHandler(registry, node);
    }
}
