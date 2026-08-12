package com.harudle.common.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfiguration {

    private static final ZoneId SERVICE_ZONE_ID = ZoneId.of("Asia/Seoul");

    TimeConfiguration() {
    }

    @Bean
    ZoneId serviceZoneId() {
        return SERVICE_ZONE_ID;
    }

    @Bean
    Clock serviceClock(ZoneId serviceZoneId) {
        return Clock.system(serviceZoneId);
    }
}
