package com.harudle.generation.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

class R2StorageConfigurationTest {

    private static final String PREFIX = "harudle.generation.storage.r2.";
    private static final String ENDPOINT = "https://00000000000000000000000000000000.r2.cloudflarestorage.com";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(R2StorageConfiguration.class);

    @Test
    @DisplayName("기본값에서는 자격 증명 없이 R2 클라이언트를 등록하지 않는다")
    void keepR2DisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(R2StorageProperties.class);
            assertThat(context).doesNotHaveBean(S3Client.class);
            assertThat(context).doesNotHaveBean(S3Presigner.class);
        });
    }

    @Test
    @DisplayName("R2를 비활성화하면 사용하지 않는 설정을 바인딩하거나 검증하지 않는다")
    void doNotBindDisabledR2Configuration() {
        contextRunner.withPropertyValues(
                PREFIX + "enabled=false",
                PREFIX + "environment=dev",
                PREFIX + "endpoint=invalid URI",
                PREFIX + "access-url-ttl=invalid duration"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(R2StorageProperties.class);
            assertThat(context).doesNotHaveBean("r2S3Client");
            assertThat(context).doesNotHaveBean("r2S3Presigner");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    @DisplayName("개발과 운영 설정으로 R2 전용 클라이언트와 GET 서명 URL을 구성한다")
    void configureR2Clients(String environment) {
        contextRunner.withPropertyValues(enabledProperties())
                .withPropertyValues(PREFIX + "environment=" + environment).run(context -> {
            assertThat(context).hasNotFailed();
            R2StorageProperties properties = context.getBean(R2StorageProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.environment()).isEqualTo(environment);
            assertThat(properties.endpoint()).isEqualTo(URI.create(ENDPOINT));
            assertThat(properties.bucket()).isEqualTo("test-backup");
            assertThat(properties.accessUrlTtl()).isEqualTo(Duration.ofMinutes(15));
            assertThat(properties.toString())
                    .contains("accessKeyId=***", "secretAccessKey=***")
                    .doesNotContain("r2-test-access-key", "r2-test-secret-key");

            S3Client client = context.getBean("r2S3Client", S3Client.class);
            assertThat(client.serviceClientConfiguration().region()).isEqualTo(Region.of("auto"));
            assertThat(client.serviceClientConfiguration().endpointOverride())
                    .contains(URI.create(ENDPOINT));
            assertThat(client.serviceClientConfiguration().credentialsProvider())
                    .isInstanceOfSatisfying(StaticCredentialsProvider.class, provider ->
                            assertThat(provider.resolveCredentials().accessKeyId()).isEqualTo("r2-test-access-key"));

            S3Presigner presigner = context.getBean("r2S3Presigner", S3Presigner.class);
            PresignedGetObjectRequest signed = presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(properties.accessUrlTtl())
                    .getObjectRequest(request -> request.bucket(properties.bucket())
                            .key("harudle/generated/diary-images/" + environment + "/diary-id/image.png"))
                    .build());
            assertThat(signed.url().getHost()).isEqualTo(URI.create(ENDPOINT).getHost());
            assertThat(signed.url().getPath())
                    .isEqualTo("/test-backup/harudle/generated/diary-images/" + environment + "/diary-id/image.png");
            assertThat(signed.url().getQuery())
                    .contains("X-Amz-Expires=900", "r2-test-access-key");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"environment", "endpoint", "bucket", "access-key-id", "secret-access-key", "access-url-ttl"})
    @DisplayName("R2를 활성화하면 필수 설정 누락을 서버 시작 단계에서 거절한다")
    void rejectMissingRequiredProperty(String property) {
        contextRunner.withPropertyValues(enabledProperties())
                .withPropertyValues(PREFIX + property + "=")
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.r2.cloudflarestorage.com",
            "https://example.r2.cloudflarestorage.com/test-backup",
            "https://user:password@example.r2.cloudflarestorage.com",
            "https://example.r2.cloudflarestorage.com?bucket=test-backup",
            "https://example.r2.cloudflarestorage.com#fragment",
            "relative-endpoint"
    })
    @DisplayName("HTTPS API 루트 주소가 아니면 R2 endpoint를 거절한다")
    void rejectInvalidEndpoint(String endpoint) {
        contextRunner.withPropertyValues(enabledProperties())
                .withPropertyValues(PREFIX + "endpoint=" + endpoint)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s", "500ms", "8d"})
    @DisplayName("접근 URL 유효기간이 1초 미만이거나 7일을 넘으면 거절한다")
    void rejectInvalidAccessUrlTtl(String ttl) {
        contextRunner.withPropertyValues(enabledProperties())
                .withPropertyValues(PREFIX + "access-url-ttl=" + ttl)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1s", "7d"})
    @DisplayName("접근 URL 유효기간의 하한과 상한을 허용한다")
    void acceptAccessUrlTtlBounds(String ttl) {
        contextRunner.withPropertyValues(enabledProperties())
                .withPropertyValues(PREFIX + "access-url-ttl=" + ttl)
                .run(context -> assertThat(context).hasNotFailed());
    }

    private static String[] enabledProperties() {
        return new String[]{
                PREFIX + "enabled=true",
                PREFIX + "environment=prod",
                PREFIX + "endpoint=" + ENDPOINT,
                PREFIX + "bucket=test-backup",
                PREFIX + "access-key-id=r2-test-access-key",
                PREFIX + "secret-access-key=r2-test-secret-key",
                PREFIX + "access-url-ttl=15m"
        };
    }
}
