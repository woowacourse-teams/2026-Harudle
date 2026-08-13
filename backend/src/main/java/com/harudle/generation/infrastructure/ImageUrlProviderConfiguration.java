package com.harudle.generation.infrastructure;

import com.harudle.generation.service.port.ImageStorageException;
import com.harudle.generation.service.port.ImageUrlProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ImageUrlProviderConfiguration {

    @Bean
    @ConditionalOnMissingBean(ImageUrlProvider.class)
    @ConditionalOnProperty(
            prefix = "harudle.generation.adapters",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true
    )
    ImageUrlProvider unavailableImageUrlProvider() {
        return imageObjectKey -> {
            throw new ImageStorageException("이미지 URL 발급 어댑터가 구성되지 않았습니다.");
        };
    }
}
