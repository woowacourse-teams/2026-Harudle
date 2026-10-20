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
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

class DiarySourceTextReadinessConfigurationTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(DiarySourceTextReadinessConfiguration.class)
            .withBean(DataSource.class, () -> dataSource)
            .withBean(DiarySourceTextCipher.class, () -> mock(DiarySourceTextCipher.class))
            .withBean(RequestFingerprintGenerator.class, RequestFingerprintGenerator::new);

    @Test
    @DisplayName("운영 앱은 DB 완료 확인에 실패하면 원인 문자열을 노출하지 않고 시작을 거절한다")
    void refuseUnverifiedProductionStartupWithoutDatabaseDiagnostics() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("sensitive-database-credentials"));
        context.withPropertyValues("spring.profiles.active=prod").run(application -> {
            assertThat(application).hasFailed().getFailure()
                    .hasRootCauseMessage("Diary source text migration is not ready.");
            StringWriter trace = new StringWriter();
            application.getStartupFailure().printStackTrace(new PrintWriter(trace));
            assertThat(trace.toString()).doesNotContain("sensitive-database-credentials");
        });
    }

    @Test
    @DisplayName("실제 배포 모드가 있으면 local 프로필로 덮어도 DB 완료 검사를 생략하지 않는다")
    void stillVerifyDeploymentAfterProfileOverride() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("private-diagnostic"));
        MockEnvironment environment = new MockEnvironment() {
            @Override
            public Map<String, Object> getSystemEnvironment() {
                return Map.of("DIARY_SOURCE_TEXT_RELEASE_MODE", "reader");
            }
        };
        environment.setActiveProfiles("local");
        context.withInitializer(application -> application.setEnvironment(environment))
                .run(application -> assertThat(application).hasFailed().getFailure()
                        .hasRootCauseMessage("Diary source text migration is not ready."));
    }

    @Test
    @DisplayName("배포가 아닌 로컬 테스트는 운영 완료 제약 없이 독립 실행한다")
    void allowIndependentLocalTests() throws SQLException {
        context.run(application -> assertThat(application).hasNotFailed());
        verify(dataSource, never()).getConnection();
    }
}
