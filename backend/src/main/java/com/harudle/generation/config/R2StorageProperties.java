package com.harudle.generation.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.Duration;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("harudle.generation.storage.r2")
public record R2StorageProperties(
        boolean enabled,
        @NotBlank @Pattern(regexp = "dev|prod", message = "R2 실행 환경은 dev 또는 prod여야 합니다.") String environment,
        @NotNull URI endpoint,
        @NotBlank String bucket,
        @NotBlank String accessKeyId,
        @NotBlank String secretAccessKey,
        @NotNull Duration accessUrlTtl,
        @NotNull DataSize maxObjectSize,
        @DefaultValue("2s") @NotNull Duration listLookupBudget,
        @DefaultValue("2s") @NotNull Duration singleLookupBudget
) {

    private static final Duration MIN_ACCESS_URL_TTL = Duration.ofSeconds(1);
    private static final Duration MAX_ACCESS_URL_TTL = Duration.ofDays(7);

    @AssertTrue(message = "목록 이미지 조회 시간 예산은 1ms 이상 10초 이하여야 합니다.")
    public boolean isListLookupBudgetValid() {
        return listLookupBudget != null
                && listLookupBudget.compareTo(Duration.ofMillis(1)) >= 0
                && listLookupBudget.compareTo(Duration.ofSeconds(10)) <= 0;
    }

    @AssertTrue(message = "단일 이미지 조회 시간 예산은 1ms 이상 10초 이하여야 합니다.")
    public boolean isSingleLookupBudgetValid() {
        return singleLookupBudget != null
                && singleLookupBudget.compareTo(Duration.ofMillis(1)) >= 0
                && singleLookupBudget.compareTo(Duration.ofSeconds(10)) <= 0;
    }

    @AssertTrue(message = "R2 endpoint는 경로, 인증 정보, 쿼리, 프래그먼트가 없는 HTTPS API 주소여야 합니다.")
    public boolean isEndpointValid() {
        return endpoint != null
                && "https".equalsIgnoreCase(endpoint.getScheme())
                && endpoint.getHost() != null
                && endpoint.getUserInfo() == null
                && endpoint.getQuery() == null
                && endpoint.getFragment() == null
                && (endpoint.getPath().isEmpty() || "/".equals(endpoint.getPath()));
    }

    @AssertTrue(message = "R2 접근 URL 유효 시간은 1초 이상 7일 이하여야 합니다.")
    public boolean isAccessUrlTtlValid() {
        return accessUrlTtl != null
                && accessUrlTtl.compareTo(MIN_ACCESS_URL_TTL) >= 0
                && accessUrlTtl.compareTo(MAX_ACCESS_URL_TTL) <= 0;
    }

    @AssertTrue(message = "R2 객체 최대 크기는 2GiB 미만의 양수여야 합니다.")
    public boolean isMaxObjectSizeValid() {
        return maxObjectSize != null && maxObjectSize.toBytes() > 0
                && maxObjectSize.toBytes() < Integer.MAX_VALUE;
    }

    @Override
    public @NonNull String toString() {
        return ("R2StorageProperties[enabled=%s, environment=%s, endpoint=%s, bucket=%s, "
                + "accessKeyId=***, secretAccessKey=***, accessUrlTtl=%s, maxObjectSize=%s, "
                + "listLookupBudget=%s, singleLookupBudget=%s]").formatted(
                enabled, environment, endpoint, bucket, accessUrlTtl, maxObjectSize, listLookupBudget, singleLookupBudget
        );
    }
}
