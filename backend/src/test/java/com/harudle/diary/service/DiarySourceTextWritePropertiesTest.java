package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.diary.infrastructure.crypto.DiarySourceTextCryptoException;
import com.harudle.diary.infrastructure.crypto.DiarySourceTextEncryptionConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DiarySourceTextWritePropertiesTest {

    private static final String ENABLED_PROPERTY = "harudle.diary.source-text-encryption.new-writes-enabled";

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(DiarySourceTextWriteConfiguration.class);

    @Test
    @DisplayName("신규 작성 설정이 없으면 기본적으로 암호화 신규 작성을 허용한다")
    void enableNewWritesByDefault() {
        context.run(application -> assertThat(application.getBean(DiarySourceTextWriteProperties.class)
                .newWritesEnabled()).isTrue());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("복구 단계의 차단과 정상 단계의 허용 설정을 각각 바인딩한다")
    void bindExplicitWritePolicy(boolean enabled) {
        context.withPropertyValues(ENABLED_PROPERTY + "=" + enabled)
                .run(application -> assertThat(application.getBean(DiarySourceTextWriteProperties.class)
                        .newWritesEnabled()).isEqualTo(enabled));
    }

    @Test
    @DisplayName("신규 작성 설정이 잘못되면 기본 허용으로 우회하지 않고 시작을 거절한다")
    void failStartupForInvalidWritePolicy() {
        context.withPropertyValues(ENABLED_PROPERTY + "=invalid")
                .run(application -> assertThat(application).hasFailed());
    }

    @Test
    @DisplayName("신규 작성을 차단해도 기존 원문을 읽을 암호화 키는 필수다")
    void stillRequireKeyRingWhenNewWritesAreDisabled() {
        context.withUserConfiguration(DiarySourceTextEncryptionConfiguration.class)
                .withPropertyValues(ENABLED_PROPERTY + "=false")
                .run(application -> assertThat(application).hasFailed()
                        .getFailure().hasRootCauseInstanceOf(DiarySourceTextCryptoException.class));
    }
}
