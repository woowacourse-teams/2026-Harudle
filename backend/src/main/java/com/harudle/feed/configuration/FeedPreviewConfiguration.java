package com.harudle.feed.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FeedPreviewProperties.class)
public class FeedPreviewConfiguration {

    @Bean
    FeedPreviewUrls feedPreviewUrls(FeedUrlProperties feeds, FeedPreviewProperties previews) {
        return new FeedPreviewUrls(feeds, previews);
    }
}
