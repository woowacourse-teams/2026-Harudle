package com.harudle.recovery;

import com.harudle.generation.config.ImageGenerationConfiguration;
import com.harudle.generation.config.ImageProviderProperties;
import com.harudle.generation.config.GeminiGenerationProperties;
import com.harudle.generation.adapter.out.gemini.DiaryImagePromptRenderer;
import com.harudle.generation.adapter.out.openai.OpenAiDiaryImageGenerator;
import com.harudle.generation.diary.service.port.DiaryImageGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;

/** Storage recovery does not require AI credentials. Opt in only for storyboard regeneration. */
@ConditionalOnProperty(prefix = "harudle.image-recovery", name = "ai-enabled", havingValue = "true")
@Import({RecoveryImageGenerationConfiguration.Gemini.class, RecoveryImageGenerationConfiguration.OpenAi.class})
class RecoveryImageGenerationConfiguration {
    @ConditionalOnProperty(prefix = "harudle.generation.image", name = "provider", havingValue = "gemini", matchIfMissing = true)
    @Import(ImageGenerationConfiguration.class)
    static class Gemini { }

    // A direct OpenAI recovery needs neither a Gemini client nor Vertex credentials.
    @ConditionalOnProperty(prefix = "harudle.generation.image", name = "provider", havingValue = "openai")
    @EnableConfigurationProperties({ImageProviderProperties.class, GeminiGenerationProperties.class})
    static class OpenAi {
        @Bean DiaryImageGenerator recoveryOpenAiImageGenerator(ImageProviderProperties properties,
                GeminiGenerationProperties imageSettings, ObjectMapper mapper, MeterRegistry metrics) {
            return new OpenAiDiaryImageGenerator(properties, imageSettings.imageAspectRatio(),
                    new DiaryImagePromptRenderer(), mapper, metrics);
        }
    }
}
