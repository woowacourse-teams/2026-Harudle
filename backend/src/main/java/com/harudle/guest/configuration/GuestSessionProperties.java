package com.harudle.guest.configuration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("harudle.guest.session")
public record GuestSessionProperties(
        @NotNull Duration ttl
) {

    @AssertTrue(message = "게스트 세션 유효 기간은 양수여야 합니다.")
    public boolean isTtlPositive() {
        return ttl != null && !ttl.isZero() && !ttl.isNegative();
    }
}
