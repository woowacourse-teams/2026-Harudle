package com.harudle.diary.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DiarySourceTextWriteProperties.class)
class DiarySourceTextWriteConfiguration {

    DiarySourceTextWriteConfiguration(
            DiarySourceTextWriteProperties properties,
            ConfigurableEnvironment environment
    ) {
        DiarySourceTextWritePolicyValidator.validate(properties, environment);
    }
}
