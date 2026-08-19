package com.harudle.guest.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GuestSessionProperties.class)
public class GuestSessionConfiguration {
}
