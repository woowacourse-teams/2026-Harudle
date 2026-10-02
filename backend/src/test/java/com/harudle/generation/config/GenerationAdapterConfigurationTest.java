package com.harudle.generation.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.genai.Client;
import com.google.genai.Models;
import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.adapter.out.gemini.GeminiDiaryImageGenerator;
import com.harudle.generation.adapter.out.gemini.GeminiFailureReporter;
import com.harudle.generation.adapter.out.gemini.GeminiStageMetrics;
import com.harudle.generation.adapter.out.gemini.GeminiStoryboardGenerator;
import com.harudle.generation.adapter.out.s3.ObservedImageStorage;
import com.harudle.generation.adapter.out.s3.ObservedImageUrlProvider;
import com.harudle.generation.adapter.out.s3.S3FailureReporter;
import com.harudle.generation.adapter.out.s3.ImageVariantEncoder;
import com.harudle.generation.adapter.out.s3.ImageUploadPreparer;
import com.harudle.generation.diary.service.port.DiaryImageGenerator;
import com.harudle.generation.diary.service.port.BackupObjectStorage;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.ImageUrlProvider;
import com.harudle.generation.diary.service.port.StoryboardGenerator;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import com.harudle.generation.diary.service.ImageBackupService;
import java.time.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import tools.jackson.databind.ObjectMapper;

class GenerationAdapterConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(GenerationAdapterConfiguration.class, ImageBackupConfiguration.class)
            .withBean(ExternalApiLogger.class, ExternalApiLogger::new)
            .withBean("serviceClock", Clock.class, Clock::systemUTC)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    @DisplayName("명시적으로 활성화하면 Gemini와 S3 어댑터를 구성한다")
    void configureGenerationAdapters() {
        contextRunner.withPropertyValues(enabledAdapterProperties()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(Client.class);
            assertThat(context).hasSingleBean(Models.class);
            assertThat(context).hasSingleBean(S3Client.class);
            assertThat(context).hasSingleBean(S3Presigner.class);
            assertThat(context).hasSingleBean(GeminiFailureReporter.class);
            assertThat(context).hasSingleBean(GeminiStageMetrics.class);
            assertThat(context).hasSingleBean(S3FailureReporter.class);
            assertThat(context).hasSingleBean(ImageVariantEncoder.class);
            assertThat(context).hasSingleBean(ImageUploadPreparer.class);
            assertThat(context).hasSingleBean(StoryboardGenerator.class);
            assertThat(context).hasSingleBean(DiaryImageGenerator.class);
            assertThat(context).hasSingleBean(ImageStorage.class);
            assertThat(context).hasSingleBean(ImageUrlProvider.class);
            assertThat(context).doesNotHaveBean(ImageBackupService.class);
            assertThat(context).doesNotHaveBean("generateDiaryImageService");

            Client client = context.getBean(Client.class);
            assertThat(client.vertexAI()).isTrue();
            assertThat(client.apiKey()).isEqualTo("test-api-key");
            assertThat(context.getBean(S3Client.class).serviceClientConfiguration().region())
                    .isEqualTo(Region.AP_NORTHEAST_2);
            assertThat(context.getBean(StoryboardGenerator.class))
                    .isInstanceOf(GeminiStoryboardGenerator.class);
            assertThat(context.getBean(DiaryImageGenerator.class))
                    .isInstanceOf(GeminiDiaryImageGenerator.class);
            assertThat(context.getBean(ImageStorage.class))
                    .isInstanceOf(ObservedImageStorage.class);
            assertThat(context.getBean(ImageUrlProvider.class))
                    .isInstanceOf(ObservedImageUrlProvider.class);
        });
    }

    @Test
    @DisplayName("R2를 함께 활성화해도 기본 이미지 저장과 URL 발급은 S3를 사용한다")
    void keepS3AsDefaultStorageWhenR2IsEnabled() {
        contextRunner.withUserConfiguration(R2StorageConfiguration.class)
                .withSystemProperties(
                        "aws.accessKeyId=s3-test-access-key",
                        "aws.secretAccessKey=s3-test-secret-key"
                )
                .withPropertyValues(enabledAdapterProperties())
                .withPropertyValues(
                        "harudle.generation.storage.s3.environment=prod",
                        "harudle.generation.storage.s3.generated-prefix=harudle/generated/diary-images/prod",
                        "harudle.generation.storage.s3.reference-prefix=harudle/references/generation/prod",
                        "harudle.generation.storage.r2.enabled=true",
                        "harudle.generation.storage.r2.environment=prod",
                        "harudle.generation.storage.r2.endpoint=https://00000000000000000000000000000000.r2.cloudflarestorage.com",
                        "harudle.generation.storage.r2.bucket=test-backup",
                        "harudle.generation.storage.r2.access-key-id=r2-test-access-key",
                        "harudle.generation.storage.r2.secret-access-key=r2-test-secret-key",
                        "harudle.generation.storage.r2.access-url-ttl=15m",
                        "harudle.generation.storage.r2.max-object-size=20MB"
                ).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(S3Client.class))
                            .containsOnlyKeys("s3Client", "r2S3Client");
                    assertThat(context.getBeansOfType(S3Presigner.class))
                            .containsOnlyKeys("s3Presigner", "r2S3Presigner");
                    assertThat(context).hasSingleBean(ImageStorage.class);
                    assertThat(context).hasSingleBean(ImageUrlProvider.class);
                    assertThat(context).hasSingleBean(BackupObjectStorage.class);
                    assertThat(context).hasSingleBean(ImageBackupService.class);

                    ImageAccessUrl accessUrl = context.getBean(ImageUrlProvider.class)
                            .createAccessUrl("harudle/generated/diary-images/prod/diary-id/image.png");
                    assertThat(accessUrl.url().getHost()).endsWith(".amazonaws.com");
                    assertThat(accessUrl.url().getQuery())
                            .contains("s3-test-access-key")
                            .doesNotContain("r2-test-access-key");

                    ImageAccessUrl backupUrl = context.getBean(BackupObjectStorage.class)
                            .createAccessUrl("harudle/generated/diary-images/prod/diary-id/image.png");
                    assertThat(backupUrl.url().getHost()).endsWith(".r2.cloudflarestorage.com");
                    assertThat(backupUrl.url().getQuery())
                            .contains("r2-test-access-key").doesNotContain("s3-test-access-key");
                });
    }

    @Test
    @DisplayName("기본값에서는 비밀값 없이 외부 생성 어댑터를 등록하지 않는다")
    void keepGenerationAdaptersDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(Client.class);
            assertThat(context).doesNotHaveBean(S3Client.class);
            assertThat(context).doesNotHaveBean(S3Presigner.class);
            assertThat(context).doesNotHaveBean(StoryboardGenerator.class);
            assertThat(context).doesNotHaveBean(DiaryImageGenerator.class);
            assertThat(context).doesNotHaveBean(ImageStorage.class);
            assertThat(context).doesNotHaveBean(ImageUrlProvider.class);
            assertThat(context).doesNotHaveBean(GeminiGenerationProperties.class);
            assertThat(context).doesNotHaveBean(S3StorageProperties.class);
        });
    }

    @Test
    @DisplayName("외부 생성 어댑터를 활성화하면 필수 비밀값을 검증한다")
    void requireSecretsWhenGenerationAdaptersAreEnabled() {
        contextRunner.withPropertyValues(
                "harudle.generation.adapters.enabled=true",
                "harudle.generation.gemini.api-key= ",
                "harudle.generation.gemini.storyboard-model=storyboard-model",
                "harudle.generation.gemini.image-model=image-model",
                "harudle.generation.gemini.storyboard-thinking-level=high",
                "harudle.generation.gemini.image-aspect-ratio=1:1",
                "harudle.generation.gemini.max-output-tokens=4096",
                "harudle.generation.gemini.retry-attempts=3",
                "harudle.generation.gemini.request-timeout=180s",
                "harudle.generation.storage.s3.bucket= ",
                "harudle.generation.storage.s3.region=ap-northeast-2",
                "harudle.generation.storage.s3.environment=dev",
                "harudle.generation.storage.s3.reference-prefix=harudle/references/generation/dev",
                "harudle.generation.storage.s3.generated-prefix=harudle/generated/diary-images/dev",
                "harudle.generation.storage.s3.max-object-size=20MB",
                "harudle.generation.storage.s3.access-url-ttl=15m"
        ).run(context -> assertThat(context).hasFailed());
    }

    private static String[] enabledAdapterProperties() {
        return new String[]{
                "harudle.generation.adapters.enabled=true",
                "harudle.generation.gemini.api-key=test-api-key",
                "harudle.generation.gemini.storyboard-model=storyboard-model",
                "harudle.generation.gemini.image-model=image-model",
                "harudle.generation.gemini.storyboard-thinking-level=high",
                "harudle.generation.gemini.image-aspect-ratio=1:1",
                "harudle.generation.gemini.max-output-tokens=4096",
                "harudle.generation.gemini.retry-attempts=3",
                "harudle.generation.gemini.request-timeout=180s",
                "harudle.generation.storage.s3.bucket=test-bucket",
                "harudle.generation.storage.s3.region=ap-northeast-2",
                "harudle.generation.storage.s3.environment=dev",
                "harudle.generation.storage.s3.reference-prefix=harudle/references/generation/dev",
                "harudle.generation.storage.s3.generated-prefix=harudle/generated/diary-images/dev",
                "harudle.generation.storage.s3.max-object-size=20MB",
                "harudle.generation.storage.s3.access-url-ttl=15m"
        };
    }
}
