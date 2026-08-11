package com.harudle.generation.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.google.genai.Client;
import com.google.genai.Models;
import com.harudle.generation.adapter.out.gemini.GeminiComicImageGenerator;
import com.harudle.generation.adapter.out.gemini.GeminiStoryboardGenerator;
import com.harudle.generation.adapter.out.s3.S3ImageStorage;
import com.harudle.generation.repository.ComicGenerationRepository;
import com.harudle.generation.repository.GenerationPromptRepository;
import com.harudle.generation.service.GenerateComicService;
import com.harudle.generation.service.RequestFingerprintGenerator;
import com.harudle.generation.service.port.ComicImageGenerator;
import com.harudle.generation.service.port.ImageStorage;
import com.harudle.generation.service.port.StoryboardGenerator;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.ObjectMapper;

class GenerationAdapterConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(GenerationAdapterConfiguration.class)
            .withBean(GeminiGenerationProperties.class, GenerationAdapterConfigurationTest::geminiProperties)
            .withBean(S3StorageProperties.class, GenerationAdapterConfigurationTest::s3Properties)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(RequestFingerprintGenerator.class, RequestFingerprintGenerator::new)
            .withBean(
                    GenerationPromptRepository.class,
                    () -> mock(GenerationPromptRepository.class)
            )
            .withBean(
                    ComicGenerationRepository.class,
                    () -> mock(ComicGenerationRepository.class)
            );

    @Test
    @DisplayName("Gemini와 S3 어댑터를 생성 서비스에 연결한다")
    void configureGenerationAdapters() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(Client.class);
            assertThat(context).hasSingleBean(Models.class);
            assertThat(context).hasSingleBean(S3Client.class);
            assertThat(context).hasSingleBean(StoryboardGenerator.class);
            assertThat(context).hasSingleBean(ComicImageGenerator.class);
            assertThat(context).hasSingleBean(ImageStorage.class);
            assertThat(context).hasSingleBean(GenerateComicService.class);

            Client client = context.getBean(Client.class);
            assertThat(client.vertexAI()).isTrue();
            assertThat(client.apiKey()).isEqualTo("test-api-key");
            assertThat(context.getBean(S3Client.class).serviceClientConfiguration().region())
                    .isEqualTo(Region.AP_NORTHEAST_2);
            assertThat(context.getBean(StoryboardGenerator.class))
                    .isInstanceOf(GeminiStoryboardGenerator.class);
            assertThat(context.getBean(ComicImageGenerator.class))
                    .isInstanceOf(GeminiComicImageGenerator.class);
            assertThat(context.getBean(ImageStorage.class))
                    .isInstanceOf(S3ImageStorage.class);
        });
    }

    private static GeminiGenerationProperties geminiProperties() {
        return new GeminiGenerationProperties(
                "test-api-key",
                "storyboard-model",
                "image-model",
                "high",
                "1:1",
                4096,
                3,
                Duration.ofSeconds(180)
        );
    }

    private static S3StorageProperties s3Properties() {
        return new S3StorageProperties(
                "test-bucket",
                "ap-northeast-2",
                "generated/comics",
                DataSize.ofMegabytes(20)
        );
    }
}
