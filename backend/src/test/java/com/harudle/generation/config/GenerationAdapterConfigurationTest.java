package com.harudle.generation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.Models;
import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.adapter.out.gemini.GeminiDiaryImageGenerator;
import com.harudle.generation.adapter.out.gemini.GeminiFailureReporter;
import com.harudle.generation.adapter.out.gemini.GeminiStageMetrics;
import com.harudle.generation.adapter.out.gemini.GeminiStoryboardGenerator;
import com.harudle.generation.adapter.out.gemini.client.ExpressGeminiClientFactory;
import com.harudle.generation.adapter.out.gemini.client.GeminiClientFactory;
import com.harudle.generation.adapter.out.gemini.client.VertexGeminiClientFactory;
import com.harudle.generation.adapter.out.s3.ObservedImageStorage;
import com.harudle.generation.adapter.out.s3.ObservedImageUrlProvider;
import com.harudle.generation.adapter.out.s3.R2FallbackImageUrlProvider;
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
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import tools.jackson.databind.ObjectMapper;

class GenerationAdapterConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(GenerationAdapterConfiguration.class, ImageBackupConfiguration.class)
            .withInitializer(context -> context.addBeanFactoryPostProcessor(
                    beanFactory -> beanFactory.registerSingleton("imageVariantEncoder", mock(ImageVariantEncoder.class))))
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
            assertThat(context).hasSingleBean(GeminiClientFactory.class);
            assertThat(context.getBean(GeminiClientFactory.class)).isInstanceOf(ExpressGeminiClientFactory.class);
            assertThat(context).doesNotHaveBean(GoogleCredentials.class);
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

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "express", " EXPRESS ", "ExPrEsS"})
    @DisplayName("빈 값과 공백을 포함한 Express 설정은 바인딩된 인증 모드와 같은 Factory를 선택한다")
    void normalizeExpressAuthMode(String authMode) {
        withRawAuthMode(contextRunner.withPropertyValues(enabledAdapterProperties()), authMode)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(GeminiClientProperties.class).authMode())
                            .isEqualTo(GeminiClientProperties.AuthMode.EXPRESS);
                    assertThat(context).hasSingleBean(GeminiClientFactory.class);
                    assertThat(context.getBean(GeminiClientFactory.class)).isInstanceOf(ExpressGeminiClientFactory.class);
                    assertThat(context).doesNotHaveBean(GoogleCredentials.class);
                    assertThat(context.getBean(Client.class).apiKey()).isEqualTo("test-api-key");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"vertex", " vertex ", " VERTEX ", "VeRtEx"})
    @DisplayName("앞뒤 공백과 대소문자가 다른 Vertex 설정도 바인딩된 인증 모드와 같은 Factory를 선택한다")
    void normalizeVertexAuthMode(String authMode) {
        withRawAuthMode(vertexContextRunner(), authMode).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(GeminiClientProperties.class).authMode())
                    .isEqualTo(GeminiClientProperties.AuthMode.VERTEX);
            assertThat(context).hasSingleBean(GeminiClientFactory.class);
            assertThat(context.getBean(GeminiClientFactory.class)).isInstanceOf(VertexGeminiClientFactory.class);
            Client client = context.getBean(Client.class);
            assertThat(client.apiKey()).isNull();
            assertThat(client.project()).isEqualTo("test-project");
            assertThat(client.location()).isEqualTo("global");
        });
    }

    @Test
    @DisplayName("Express 모드에서는 등록된 ADC 자격 증명도 초기화하지 않는다")
    void skipCredentialsInExpressMode() {
        contextRunner.withPropertyValues(enabledAdapterProperties())
                .withBean("geminiCredentials", GoogleCredentials.class, () -> {
                    throw new AssertionError("Express 모드에서는 ADC를 조회하면 안 됩니다.");
                }, beanDefinition -> beanDefinition.setLazyInit(true))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(GeminiClientFactory.class)).isInstanceOf(ExpressGeminiClientFactory.class);
                });
    }

    @Test
    @DisplayName("Vertex 모드는 API Key 없이 ADC와 프로젝트·global로 기존 생성 어댑터를 구성한다")
    void configureVertexAdaptersWithoutApiKey() {
        vertexContextRunner().withPropertyValues("harudle.generation.gemini.api-key=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(GeminiClientFactory.class);
                    assertThat(context.getBean(GeminiClientFactory.class)).isInstanceOf(VertexGeminiClientFactory.class);
                    assertThat(context).hasSingleBean(Client.class);
                    Client client = context.getBean(Client.class);
                    assertThat(client.vertexAI()).isTrue();
                    assertThat(client.apiKey()).isNull();
                    assertThat(client.project()).isEqualTo("test-project");
                    assertThat(client.location()).isEqualTo("global");
                    assertThat(context.getBean(GeminiGenerationProperties.class).imageModel())
                            .isEqualTo("gemini-nano-banana-2.1");
                    assertThat(context.getBean(StoryboardGenerator.class)).isInstanceOf(GeminiStoryboardGenerator.class);
                    assertThat(context.getBean(DiaryImageGenerator.class)).isInstanceOf(GeminiDiaryImageGenerator.class);
                });
    }

    @Test
    @DisplayName("Vertex 모드에서는 기존 API Key 설정이 남아 있어도 사용하지 않는다")
    void ignoreApiKeyInVertexMode() {
        vertexContextRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(Client.class).apiKey()).isNull();
        });
    }

    @Test
    @DisplayName("R2를 함께 활성화해도 기본 이미지 저장과 URL 발급은 S3를 사용한다")
    void keepS3AsDefaultStorageWhenR2IsEnabled() {
        S3Client sourceClient = mock(S3Client.class);
        when(sourceClient.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());
        contextRunner.withInitializer(context -> context.addBeanFactoryPostProcessor(
                        beanFactory -> beanFactory.registerSingleton("s3Client", sourceClient)))
                .withUserConfiguration(R2StorageConfiguration.class)
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
                    assertThat(context.getBean(ImageUrlProvider.class)).isInstanceOf(R2FallbackImageUrlProvider.class);
                    assertThat(context).hasSingleBean(BackupObjectStorage.class);
                    assertThat(context).hasSingleBean(ImageBackupService.class);

                    ImageAccessUrl accessUrl = context.getBean(ImageUrlProvider.class)
                            .createAccessUrl("harudle/generated/diary-images/prod/diary-id/image.png");
                    assertThat(accessUrl.url().getHost()).endsWith(".amazonaws.com");
                    assertThat(accessUrl.url().getQuery())
                            .contains("s3-test-access-key")
                            .doesNotContain("r2-test-access-key");
                    verify(sourceClient).headObject(any(HeadObjectRequest.class));

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
            assertThat(context).doesNotHaveBean(GeminiClientFactory.class);
            assertThat(context).doesNotHaveBean(GoogleCredentials.class);
            assertThat(context).doesNotHaveBean(S3Client.class);
            assertThat(context).doesNotHaveBean(S3Presigner.class);
            assertThat(context).doesNotHaveBean(StoryboardGenerator.class);
            assertThat(context).doesNotHaveBean(DiaryImageGenerator.class);
            assertThat(context).doesNotHaveBean(ImageStorage.class);
            assertThat(context).doesNotHaveBean(ImageUrlProvider.class);
            assertThat(context).doesNotHaveBean(GeminiGenerationProperties.class);
            assertThat(context).doesNotHaveBean(GeminiClientProperties.class);
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

    private ApplicationContextRunner withRawAuthMode(ApplicationContextRunner runner, String authMode) {
        return runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("auth-mode-test", Map.of("harudle.generation.gemini.auth-mode", authMode))));
    }

    private ApplicationContextRunner vertexContextRunner() {
        return contextRunner.withBean("geminiCredentials", GoogleCredentials.class, () -> GoogleCredentials.create(
                        new AccessToken("test-access-token", Date.from(Instant.now().plusSeconds(3600)))))
                .withPropertyValues(enabledAdapterProperties())
                .withPropertyValues(
                        "harudle.generation.gemini.auth-mode=vertex",
                        "harudle.generation.gemini.project-id=test-project",
                        "harudle.generation.gemini.location=global",
                        "harudle.generation.gemini.image-model=gemini-nano-banana-2.1"
                );
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
