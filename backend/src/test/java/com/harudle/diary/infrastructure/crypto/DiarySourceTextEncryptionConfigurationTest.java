package com.harudle.diary.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.diary.domain.DiarySourceTextCipher;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DiarySourceTextEncryptionConfigurationTest {

    private static final String PREFIX = "harudle.diary.source-text-encryption.";
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @TempDir
    private Path directory;

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(DiarySourceTextEncryptionConfiguration.class);

    @Test
    @DisplayName("암호화 설정을 독립적으로 바인딩하고 암호화 빈을 등록한다")
    void independentlyRegistersAndBindsCipher() {
        context.withPropertyValues(PREFIX + "active-key-id=current", PREFIX + "keys.current=" + KEY)
                .run(application -> {
                    assertThat(application).hasNotFailed().hasSingleBean(DiarySourceTextCipher.class)
                            .hasSingleBean(DiarySourceTextEncryptionProperties.class);
                    DiarySourceTextCipher cipher = application.getBean(DiarySourceTextCipher.class);
                    UUID diaryId = UUID.randomUUID();
                    UUID userId = UUID.randomUUID();
                    String stored = cipher.encrypt(diaryId, userId, "설정으로 등록한 암호화");
                    assertThat(cipher.decrypt(diaryId, userId, stored, 1)).isEqualTo("설정으로 등록한 암호화");
                });
    }

    @Test
    @DisplayName("키 설정이 없으면 애플리케이션 시작을 차단한다")
    void refusesStartupWhenKeyRingIsMissing() {
        context.run(application -> assertThat(application).hasFailed()
                .getFailure().hasRootCauseInstanceOf(DiarySourceTextCryptoException.class));
    }

    @Test
    @DisplayName("키 설정이 잘못되면 비밀값을 노출하지 않고 애플리케이션 시작을 차단한다")
    void refusesStartupForInvalidKeysWithoutLeakingTheirValues() {
        String invalidKey = "sensitive-invalid-key!";
        context.withPropertyValues(PREFIX + "active-key-id=current", PREFIX + "keys.current=" + invalidKey)
                .run(application -> {
                    assertThat(application).hasFailed().getFailure()
                            .hasRootCauseInstanceOf(DiarySourceTextCryptoException.class);
                    StringWriter failure = new StringWriter();
                    application.getStartupFailure().printStackTrace(new PrintWriter(failure));
                    assertThat(failure.toString()).doesNotContain(invalidKey);
                });
    }

    @Test
    @DisplayName("운영 프로필은 환경에 맞는 영속 검증 자료가 있어야 암호화 빈을 제공한다")
    void productionProfileVerifiesPersistedEvidence() {
        Path file = directory.resolve("keys.json");
        new DiarySourceTextKeyVerification(new DiarySourceTextEncryptionProperties("current", Map.of("current", KEY)))
                .createInitialEvidence(file, "dev");

        production(file, "dev", KEY).run(application -> assertThat(application)
                .hasNotFailed().hasSingleBean(DiarySourceTextCipher.class));
    }

    @Test
    @DisplayName("운영 프로필은 검증 자료 누락이나 같은 ID의 다른 키를 시작 전에 거절한다")
    void productionProfileRefusesMissingEvidenceAndWrongKey() {
        Path file = directory.resolve("keys.json");
        production(file, "dev", KEY).run(application -> assertThat(application).hasFailed());
        new DiarySourceTextKeyVerification(new DiarySourceTextEncryptionProperties("current", Map.of("current", KEY)))
                .createInitialEvidence(file, "dev");
        byte[] wrongKeyBytes = new byte[32];
        wrongKeyBytes[0] = 1;
        String wrongKey = Base64.getEncoder().encodeToString(wrongKeyBytes);
        production(file, "dev", wrongKey).run(application -> assertThat(application).hasFailed());
    }

    @Test
    @DisplayName("운영 프로필은 검증 경로와 dev 또는 prod 배포 환경을 필수로 요구한다")
    void productionProfileRequiresPathAndDeploymentEnvironment() {
        context.withPropertyValues(PREFIX + "active-key-id=current", PREFIX + "keys.current=" + KEY,
                        "spring.profiles.active=prod", "DEPLOY_ENV=dev")
                .run(application -> assertThat(application).hasFailed());
        Path file = directory.resolve("keys.json");
        new DiarySourceTextKeyVerification(new DiarySourceTextEncryptionProperties("current", Map.of("current", KEY)))
                .createInitialEvidence(file, "dev");
        production(file, "", KEY).run(application -> assertThat(application).hasFailed());
        production(file, "prod", KEY).run(application -> assertThat(application).hasFailed());
    }

    private ApplicationContextRunner production(Path file, String environment, String key) {
        return context.withPropertyValues(PREFIX + "active-key-id=current", PREFIX + "keys.current=" + key,
                "spring.profiles.active=prod", "DEPLOY_ENV=" + environment, PREFIX + "verification-file=" + file);
    }
}
