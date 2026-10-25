package com.harudle.diary.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.diary.domain.DiarySourceTextCipher;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

class DiarySourceTextEncryptionConfigurationTest {

    private static final String PREFIX = "harudle.diary.source-text-encryption.";
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String NEW_KEY = newKey();

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

    @ParameterizedTest
    @ValueSource(strings = {"old", "new"})
    @DisplayName("새 키 등록 후 구키나 신키만 가진 운영 앱은 재시작을 거절한다")
    void refusesIncompleteKeyRingsAfterRegistration(String retainedKey) {
        Path file = registerNewKey();
        String material = "old".equals(retainedKey) ? KEY : NEW_KEY;

        production(file, retainedKey, Map.of(retainedKey, material)).run(application -> assertThat(application)
                .hasFailed().getFailure().hasRootCauseInstanceOf(DiarySourceTextCryptoException.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"old", "new"})
    @DisplayName("새 키 등록 후 전체 키링을 가진 운영 앱은 구키나 신키를 활성화해 시작할 수 있다")
    void allowsEitherActiveKeyWithCompleteRegisteredRing(String activeKey) {
        Path file = registerNewKey();

        production(file, activeKey, Map.of("old", KEY, "new", NEW_KEY)).run(application -> assertThat(application)
                .hasNotFailed().hasSingleBean(DiarySourceTextCipher.class));
    }

    private Path registerNewKey() {
        Path file = directory.resolve("keys.json");
        new DiarySourceTextKeyVerification(new DiarySourceTextEncryptionProperties("old", Map.of("old", KEY)))
                .createInitialEvidence(file, "dev");
        new DiarySourceTextKeyVerification(
                new DiarySourceTextEncryptionProperties("old", Map.of("old", KEY, "new", NEW_KEY))
        ).register(file, "dev");
        return file;
    }

    private ApplicationContextRunner production(Path file, String activeKey, Map<String, String> keys) {
        ApplicationContextRunner configured = context.withPropertyValues(PREFIX + "active-key-id=" + activeKey,
                "spring.profiles.active=prod", "DEPLOY_ENV=dev", PREFIX + "verification-file=" + file);
        for (Map.Entry<String, String> key : keys.entrySet()) {
            configured = configured.withPropertyValues(PREFIX + "keys." + key.getKey() + "=" + key.getValue());
        }
        return configured;
    }

    private static String newKey() {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) 1);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"reader", "writer", ""})
    @DisplayName("실제 배포 모드가 있으면 local 프로필로 덮어도 검증 자료 누락과 같은 ID의 잘못된 키를 거절한다")
    void deployedModeVerifiesEvidenceDespiteLocalProfileOverride(String releaseMode) {
        Path file = directory.resolve("deployment-keys.json");
        MockEnvironment environment = new MockEnvironment() {
            @Override
            public Map<String, Object> getSystemEnvironment() {
                return Map.of("DIARY_SOURCE_TEXT_RELEASE_MODE", releaseMode);
            }
        };
        environment.setActiveProfiles("local");
        environment.setProperty(PREFIX + "active-key-id", "current");
        environment.setProperty(PREFIX + "keys.current", KEY);
        environment.setProperty(PREFIX + "verification-file", file.toString());
        environment.setProperty("DEPLOY_ENV", "dev");
        ApplicationContextRunner deployed = context.withInitializer(application -> application.setEnvironment(environment));

        deployed.run(application -> assertThat(application).hasFailed().getFailure()
                .hasRootCauseInstanceOf(DiarySourceTextCryptoException.class));
        new DiarySourceTextKeyVerification(new DiarySourceTextEncryptionProperties("current", Map.of("current", KEY)))
                .createInitialEvidence(file, "dev");
        byte[] wrongKeyBytes = new byte[32];
        wrongKeyBytes[0] = 1;
        environment.setProperty(PREFIX + "keys.current", Base64.getEncoder().encodeToString(wrongKeyBytes));
        deployed.run(application -> assertThat(application).hasFailed().getFailure()
                .hasRootCauseInstanceOf(DiarySourceTextCryptoException.class));

        environment.setProperty(PREFIX + "keys.current", KEY);
        deployed.run(application -> assertThat(application)
                .hasNotFailed().hasSingleBean(DiarySourceTextCipher.class));
    }

    private ApplicationContextRunner production(Path file, String environment, String key) {
        return context.withPropertyValues(PREFIX + "active-key-id=current", PREFIX + "keys.current=" + key,
                "spring.profiles.active=prod", "DEPLOY_ENV=" + environment, PREFIX + "verification-file=" + file);
    }
}
