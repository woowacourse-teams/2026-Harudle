package com.harudle.diary.infrastructure.crypto;

import com.harudle.diary.domain.DiarySourceTextCipher;
import java.nio.file.Path;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DiarySourceTextEncryptionProperties.class)
public class DiarySourceTextEncryptionConfiguration {

    @Bean
    public DiarySourceTextCipher diarySourceTextCipher(
            DiarySourceTextEncryptionProperties properties,
            ConfigurableEnvironment environment
    ) {
        if (environment.matchesProfiles("prod")
                || environment.getSystemEnvironment().containsKey("DIARY_SOURCE_TEXT_RELEASE_MODE")) {
            verifyProductionKeys(properties, environment);
        }
        return new AesGcmDiarySourceTextCipher(properties);
    }

    private void verifyProductionKeys(
            DiarySourceTextEncryptionProperties properties,
            ConfigurableEnvironment environment
    ) {
        try {
            String file = environment.getRequiredProperty("harudle.diary.source-text-encryption.verification-file");
            new DiarySourceTextKeyVerification(properties).verify(Path.of(file), environment.getProperty("DEPLOY_ENV"));
        } catch (RuntimeException exception) {
            throw new DiarySourceTextCryptoException();
        }
    }
}
