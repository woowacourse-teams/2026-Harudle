package com.harudle.common.security;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AuthTimeConfiguration {

    @Bean
    public Clock authClock() {
        return Clock.systemUTC();
    }

}
