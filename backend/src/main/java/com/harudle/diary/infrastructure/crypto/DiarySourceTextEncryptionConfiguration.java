package com.harudle.diary.infrastructure.crypto;

import com.harudle.diary.domain.DiarySourceTextCipher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DiarySourceTextEncryptionProperties.class)
public class DiarySourceTextEncryptionConfiguration {

    @Bean
    public DiarySourceTextCipher diarySourceTextCipher(DiarySourceTextEncryptionProperties properties) {
        return new AesGcmDiarySourceTextCipher(properties);
    }
}
