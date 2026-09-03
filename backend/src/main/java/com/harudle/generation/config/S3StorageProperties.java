package com.harudle.generation.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("harudle.generation.storage.s3")
public record S3StorageProperties(
        @NotBlank String bucket,
        @NotBlank String region,
        @NotBlank String generatedPrefix,
        @NotNull DataSize maxObjectSize,
        @NotNull Duration accessUrlTtl
) {

    private static final Duration MAX_ACCESS_URL_TTL = Duration.ofDays(7);

    @AssertTrue(message = "S3 객체 최대 크기는 양수여야 합니다.")
    public boolean isMaxObjectSizePositive() {
        return maxObjectSize != null && maxObjectSize.toBytes() > 0;
    }

    @AssertTrue(message = "S3 접근 URL 유효 시간은 0초 초과 7일 이하여야 합니다.")
    public boolean isAccessUrlTtlValid() {
        return accessUrlTtl != null
                && !accessUrlTtl.isZero()
                && !accessUrlTtl.isNegative()
                && accessUrlTtl.compareTo(MAX_ACCESS_URL_TTL) <= 0;
    }
}
