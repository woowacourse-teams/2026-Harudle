package com.harudle.common.persistence;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "harudle.database")
public record DatabaseLockProperties(Duration interactionLockTimeout) {
    public DatabaseLockProperties {
        if (interactionLockTimeout == null) {
            interactionLockTimeout = Duration.ofSeconds(1);
        }
        long milliseconds = interactionLockTimeout.toMillis();
        if (milliseconds < 1 || milliseconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("접촉 요청의 잠금 대기 제한은 1~2147483647밀리초여야 합니다.");
        }
    }
}
