package com.harudle.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.genai.Client;
import com.harudle.generation.adapter.out.openai.OpenAiDiaryImageGenerator;
import com.harudle.generation.config.GeminiClientProperties;
import com.harudle.generation.diary.service.port.DiaryImageGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

class RecoveryImageGenerationConfigurationTest {
    @Test
    void directOpenAiRecoveryDoesNotCreateGeminiOrRequireItsCredentials() {
        new ApplicationContextRunner().withUserConfiguration(RecoveryImageGenerationConfiguration.class)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("harudle.image-recovery.ai-enabled=true", "harudle.generation.image.provider=openai",
                        "harudle.generation.image.openai-api-key=public-test-key",
                        "harudle.generation.gemini.storyboard-model=unused", "harudle.generation.gemini.image-model=unused",
                        "harudle.generation.gemini.storyboard-thinking-level=low", "harudle.generation.gemini.image-aspect-ratio=2:3",
                        "harudle.generation.gemini.max-output-tokens=100", "harudle.generation.gemini.retry-attempts=1",
                        "harudle.generation.gemini.request-timeout=10s")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(DiaryImageGenerator.class);
                    assertThat(context.getBean(DiaryImageGenerator.class)).isInstanceOf(OpenAiDiaryImageGenerator.class);
                    assertThat(context).doesNotHaveBean(Client.class).doesNotHaveBean(GeminiClientProperties.class);
                });
    }
}
