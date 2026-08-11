package com.harudle.generation.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
                "harudle.generation.storage.s3.generated-prefix=generated/comics",
                "harudle.generation.storage.s3.max-object-size=20MB"
        ).run(context -> {
            assertThat(context).hasNotFailed();

            S3StorageProperties properties = context.getBean(S3StorageProperties.class);
            assertThat(properties.bucket()).isEqualTo("test-bucket");
            assertThat(properties.region()).isEqualTo("ap-northeast-2");
            assertThat(properties.generatedPrefix()).isEqualTo("generated/comics");
            assertThat(properties.maxObjectSize()).isEqualTo(DataSize.ofMegabytes(20));
        });
    }

    @Test
    @DisplayName("S3 bucket이 비어 있으면 설정 바인딩에 실패한다")
    void rejectBlankBucket() {
        contextRunner.withPropertyValues(
                "harudle.generation.storage.s3.bucket= ",
                "harudle.generation.storage.s3.region=ap-northeast-2",
                "harudle.generation.storage.s3.generated-prefix=generated/comics",
                "harudle.generation.storage.s3.max-object-size=20MB"
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
                "harudle.generation.storage.s3.generated-prefix=generated/comics",
                "harudle.generation.storage.s3.max-object-size=0B"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(S3StorageProperties.class)
    static class TestConfiguration {
    }
}
