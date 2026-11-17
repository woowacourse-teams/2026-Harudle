package com.harudle.generation.config;

import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.adapter.out.openai.OpenAiChatClient;
import com.harudle.generation.adapter.out.openai.OpenAiStageMetrics;
import com.harudle.generation.adapter.out.openai.OpenAiStoryboardGenerator;
import com.harudle.generation.adapter.out.storyboard.StoryboardJsonCodec;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** 스토리보드 메인 또는 보조로 openai를 선택했을 때만 OpenAI 스토리보드 구현을 등록한다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "harudle.generation.adapters", name = "enabled", havingValue = "true")
@Conditional(OpenAiStoryboardSelectedCondition.class)
@EnableConfigurationProperties(OpenAiStoryboardProperties.class)
public class OpenAiStoryboardConfiguration {

    @Bean(destroyMethod = "close")
    public OpenAiChatClient openAiStoryboardChatClient(OpenAiStoryboardProperties properties,
            StoryboardProviderProperties providers, ObjectMapper objectMapper) {
        return new OpenAiChatClient(OpenAiChatClient.httpClient(providers.timeoutFor("openai")),
                objectMapper, properties.apiKey());
    }

    @Bean
    public OpenAiStageMetrics openAiStageMetrics(OpenAiStoryboardProperties properties, MeterRegistry meterRegistry) {
        return new OpenAiStageMetrics(meterRegistry, properties.storyboardModel());
    }

    /** 이름은 StoryboardGenerationConfiguration이 "{provider}StoryboardGenerator"로 찾는 규칙을 따른다. */
    @Bean
    public OpenAiStoryboardGenerator openaiStoryboardGenerator(OpenAiChatClient openAiStoryboardChatClient,
            OpenAiStoryboardProperties properties, StoryboardJsonCodec codec, ExternalApiLogger logger,
            OpenAiStageMetrics openAiStageMetrics) {
        return new OpenAiStoryboardGenerator(openAiStoryboardChatClient, properties.settings(), codec, logger,
                openAiStageMetrics);
    }
}
