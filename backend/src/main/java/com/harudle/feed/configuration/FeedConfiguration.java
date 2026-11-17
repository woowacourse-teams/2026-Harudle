package com.harudle.feed.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({FeedUrlProperties.class, FeedPreviewProperties.class})
public class FeedConfiguration {

    @Bean
    FeedPreviewUrls feedPreviewUrls(FeedUrlProperties feeds, FeedPreviewProperties previews) {
        return new FeedPreviewUrls(feeds, previews);
    }
}
