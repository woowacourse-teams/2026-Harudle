package com.harudle.campus.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CampusLinkProperties.class)
public class CampusLinkConfiguration {
}
