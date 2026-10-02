package com.harudle.generation.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

class S3StoragePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    @DisplayName("S3 저장소 설정을 바인딩한다")
    void bindS3StorageProperties() {
        contextRunner.withPropertyValues(
                "harudle.generation.storage.s3.bucket=test-bucket",
                "harudle.generation.storage.s3.region=ap-northeast-2",
                "harudle.generation.storage.s3.environment=dev",
                "harudle.generation.storage.s3.reference-prefix=harudle/references/generation/dev",
                "harudle.generation.storage.s3.generated-prefix=harudle/generated/diary-images/dev",
                "harudle.generation.storage.s3.max-object-size=20MB",
                "harudle.generation.storage.s3.access-url-ttl=15m"
        ).run(context -> {
            assertThat(context).hasNotFailed();

            S3StorageProperties properties = context.getBean(S3StorageProperties.class);
            assertThat(properties.bucket()).isEqualTo("test-bucket");
            assertThat(properties.region()).isEqualTo("ap-northeast-2");
            assertThat(properties.generatedPrefix()).isEqualTo("harudle/generated/diary-images/dev");
            assertThat(properties.maxObjectSize()).isEqualTo(DataSize.ofMegabytes(20));
            assertThat(properties.accessUrlTtl()).isEqualTo(Duration.ofMinutes(15));
        });
    }

    @Test
    @DisplayName("S3 bucket이 비어 있으면 설정 바인딩에 실패한다")
    void rejectBlankBucket() {
        contextRunner.withPropertyValues(
                "harudle.generation.storage.s3.bucket= ",
                "harudle.generation.storage.s3.region=ap-northeast-2",
                "harudle.generation.storage.s3.environment=dev",
                "harudle.generation.storage.s3.reference-prefix=harudle/references/generation/dev",
                "harudle.generation.storage.s3.generated-prefix=harudle/generated/diary-images/dev",
                "harudle.generation.storage.s3.max-object-size=20MB",
                "harudle.generation.storage.s3.access-url-ttl=15m"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("harudle.generation.storage.s3");
        });
    }

    @Test
    @DisplayName("S3 객체 최대 크기가 양수가 아니면 설정 바인딩에 실패한다")
    void rejectNonPositiveMaxObjectSize() {
        contextRunner.withPropertyValues(
                "harudle.generation.storage.s3.bucket=test-bucket",
                "harudle.generation.storage.s3.region=ap-northeast-2",
                "harudle.generation.storage.s3.environment=dev",
                "harudle.generation.storage.s3.reference-prefix=harudle/references/generation/dev",
                "harudle.generation.storage.s3.generated-prefix=harudle/generated/diary-images/dev",
                "harudle.generation.storage.s3.max-object-size=0B",
                "harudle.generation.storage.s3.access-url-ttl=15m"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("S3 접근 URL 유효 시간이 허용 범위를 벗어나면 설정 바인딩에 실패한다")
    void rejectInvalidAccessUrlTtl() {
        contextRunner.withPropertyValues(
                "harudle.generation.storage.s3.bucket=test-bucket",
                "harudle.generation.storage.s3.region=ap-northeast-2",
                "harudle.generation.storage.s3.environment=dev",
                "harudle.generation.storage.s3.reference-prefix=harudle/references/generation/dev",
                "harudle.generation.storage.s3.generated-prefix=harudle/generated/diary-images/dev",
                "harudle.generation.storage.s3.max-object-size=20MB",
                "harudle.generation.storage.s3.access-url-ttl=8d"
        ).run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "environment=", "environment=staging", "environment=prod",
            "generated-prefix=harudle/generated/diary-images",
            "generated-prefix=harudle/generated/diary-images/prod",
            "generated-prefix=harudle/generated/diary-images/dev/",
            "reference-prefix=harudle/references/generation/prod",
            "reference-prefix="
    })
    @DisplayName("환경이 누락되거나 이미지 경로와 다르면 서버 시작을 막는다")
    void rejectsMismatchedEnvironment(String override) {
        validEnvironment("dev")
                .withPropertyValues("harudle.generation.storage.s3." + override)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    @DisplayName("dev와 prod의 생성·기준 이미지 경로가 모두 일치하면 시작한다")
    void acceptsMatchingEnvironment(String environment) {
        validEnvironment(environment).run(context -> assertThat(context).hasNotFailed());
    }

    private ApplicationContextRunner validEnvironment(String environment) {
        String prefix = "harudle.generation.storage.s3.";
        return contextRunner.withPropertyValues(
                prefix + "bucket=test-bucket", prefix + "region=ap-northeast-2",
                prefix + "environment=" + environment,
                prefix + "generated-prefix=harudle/generated/diary-images/" + environment,
                prefix + "reference-prefix=harudle/references/generation/" + environment,
                prefix + "max-object-size=20MB", prefix + "access-url-ttl=15m"
        );
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(S3StorageProperties.class)
    static class TestConfiguration {
    }
}
