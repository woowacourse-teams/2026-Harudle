package com.harudle.diary.infrastructure.crypto;

import com.harudle.diary.domain.DiarySourceTextCipher;
import java.nio.file.Path;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DiarySourceTextEncryptionProperties.class)
public class DiarySourceTextEncryptionConfiguration {

    @Bean
    public DiarySourceTextCipher diarySourceTextCipher(
            DiarySourceTextEncryptionProperties properties,
            Environment environment
    ) {
        if (environment.matchesProfiles("prod")) {
            verifyProductionKeys(properties, environment);
        }
        return new AesGcmDiarySourceTextCipher(properties);
    }

    private void verifyProductionKeys(DiarySourceTextEncryptionProperties properties, Environment environment) {
        try {
            String file = environment.getRequiredProperty("harudle.diary.source-text-encryption.verification-file");
            new DiarySourceTextKeyVerification(properties).verify(Path.of(file), environment.getProperty("DEPLOY_ENV"));
        } catch (RuntimeException exception) {
            throw new DiarySourceTextCryptoException();
        }
    }
}
