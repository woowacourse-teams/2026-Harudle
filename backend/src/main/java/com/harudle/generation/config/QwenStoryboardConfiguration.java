package com.harudle.generation.config;

import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.adapter.out.qwen.QwenChatClient;
import com.harudle.generation.adapter.out.qwen.QwenStageMetrics;
import com.harudle.generation.adapter.out.qwen.QwenStoryboardGenerator;
import com.harudle.generation.adapter.out.storyboard.StoryboardJsonCodec;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "harudle.generation.adapters", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "harudle.generation.storyboard", name = "provider", havingValue = "qwen")
@EnableConfigurationProperties(QwenStoryboardProperties.class)
public class QwenStoryboardConfiguration {

    @Bean(destroyMethod = "close")
    public QwenChatClient qwenChatClient(QwenStoryboardProperties properties, ObjectMapper objectMapper) {
        return new QwenChatClient(QwenChatClient.httpClient(properties.requestTimeout()),
                objectMapper, properties.apiKey());
    }

    @Bean
    public QwenStageMetrics qwenStageMetrics(QwenStoryboardProperties properties, MeterRegistry meterRegistry) {
        return new QwenStageMetrics(meterRegistry, properties.storyboardModel());
    }

    @Bean
    public QwenStoryboardGenerator qwenStoryboardGenerator(QwenChatClient client, QwenStoryboardProperties properties,
            StoryboardJsonCodec codec, ExternalApiLogger logger, QwenStageMetrics stageMetrics) {
        return new QwenStoryboardGenerator(client, properties.settings(), codec, logger, stageMetrics);
    }
}
