package com.harudle.generation.configuration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("harudle.generation.storage.s3")
public record S3StorageProperties(
        @NotBlank String bucket,
        @NotBlank String region,
        @NotBlank String generatedPrefix,
        @NotNull DataSize maxObjectSize
) {

    @AssertTrue(message = "S3 객체 최대 크기는 양수여야 합니다.")
    public boolean isMaxObjectSizePositive() {
        return maxObjectSize != null && maxObjectSize.toBytes() > 0;
    }
}
