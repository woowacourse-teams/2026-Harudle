package com.harudle.diary.infrastructure.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.diary.domain.DiarySourceTextCipher;
import com.harudle.generation.diary.service.RequestFingerprintGenerator;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

class DiarySourceTextReadinessConfigurationTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(DiarySourceTextReadinessConfiguration.class)
            .withBean(DataSource.class, () -> dataSource)
            .withBean(DiarySourceTextCipher.class, () -> mock(DiarySourceTextCipher.class))
            .withBean(RequestFingerprintGenerator.class, RequestFingerprintGenerator::new);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("운영 앱은 지연 초기화 여부와 관계없이 DB 완료 확인에 실패하면 원인을 숨기고 시작을 거절한다")
    void refuseUnverifiedProductionStartupWithoutDatabaseDiagnostics(boolean lazyInitialization) throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("sensitive-database-credentials"));
        withLazyInitialization(lazyInitialization).withPropertyValues("spring.profiles.active=prod")
                .run(application -> {
                    assertThat(application).hasFailed().getFailure()
                            .hasRootCauseMessage("Diary source text migration is not ready.");
                    StringWriter trace = new StringWriter();
                    application.getStartupFailure().printStackTrace(new PrintWriter(trace));
                    assertThat(trace.toString()).doesNotContain("sensitive-database-credentials");
                });
        verify(dataSource).getConnection();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("실제 배포 모드가 있으면 local 프로필과 지연 초기화 설정으로 DB 완료 검사를 생략하지 않는다")
    void stillVerifyDeploymentAfterProfileOverride(boolean lazyInitialization) throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("private-diagnostic"));
        MockEnvironment environment = new MockEnvironment() {
            @Override
            public Map<String, Object> getSystemEnvironment() {
                return Map.of("DIARY_SOURCE_TEXT_RELEASE_MODE", "reader");
            }
        };
        environment.setActiveProfiles("local");
        environment.setProperty("spring.main.lazy-initialization", Boolean.toString(lazyInitialization));
        withLazyInitialization(lazyInitialization)
                .withInitializer(application -> application.setEnvironment(environment))
                .run(application -> assertThat(application).hasFailed().getFailure()
                        .hasRootCauseMessage("Diary source text migration is not ready."));
        verify(dataSource).getConnection();
    }

    @Test
    @DisplayName("배포가 아닌 로컬 테스트는 운영 완료 제약 없이 독립 실행한다")
    void allowIndependentLocalTests() throws SQLException {
        context.run(application -> assertThat(application).hasNotFailed());
        verify(dataSource, never()).getConnection();
    }

    private ApplicationContextRunner withLazyInitialization(boolean enabled) {
        ApplicationContextRunner configured = context.withPropertyValues("spring.main.lazy-initialization=" + enabled);
        if (enabled) {
            return configured.withInitializer(application -> application.addBeanFactoryPostProcessor(
                    new LazyInitializationBeanFactoryPostProcessor()
            ));
        }
        return configured;
    }
}
