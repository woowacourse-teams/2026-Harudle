package com.harudle.diary.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

class DiarySourceTextFlywayConfigurationTest {

    private final DiarySourceTextFlywayConfiguration subject = new DiarySourceTextFlywayConfiguration();
    private final JdbcConnectionDetails application = mock(JdbcConnectionDetails.class);
    private final FlywayConnectionDetails migration = mock(FlywayConnectionDetails.class);
    private final DataSource applicationPool = mock(DataSource.class);

    @Test
    @DisplayName("Flyway 연결 정보가 없으면 실제 JDBC 연결 정보로 별도 비풀링 연결을 만든다")
    void isolateConnectionsUsingActualJdbcDetails() {
        when(application.getJdbcUrl()).thenReturn("jdbc:postgresql://jdbc-service:5432/harudle");
        when(application.getUsername()).thenReturn("application-user");
        when(application.getPassword()).thenReturn("application-password");
        FluentConfiguration configuration = Flyway.configure().dataSource(applicationPool);

        subject.diarySourceTextFlywayConnectionIsolation(application, migration).customize(configuration);

        assertThat(configuration.getDataSource()).isNotSameAs(applicationPool)
                .isInstanceOfSatisfying(SimpleDriverDataSource.class, source -> {
                    assertThat(source.getUrl()).isEqualTo("jdbc:postgresql://jdbc-service:5432/harudle");
                    assertThat(source.getUsername()).isEqualTo("application-user");
                    assertThat(source.getPassword()).isEqualTo("application-password");
                });
        verifyNoInteractions(applicationPool);
    }

    @Test
    @DisplayName("명시한 Flyway 또는 서비스 연결 정보가 있으면 전용 연결에 우선 적용한다")
    void preserveExplicitFlywayAndServiceConnectionDetails() {
        when(application.getJdbcUrl()).thenReturn("jdbc:postgresql://application:5432/harudle");
        when(application.getUsername()).thenReturn("application-user");
        when(application.getPassword()).thenReturn("application-password");
        when(migration.getJdbcUrl()).thenReturn("jdbc:postgresql://migration-service:5432/harudle");
        when(migration.getUsername()).thenReturn("migration-user");
        when(migration.getPassword()).thenReturn("migration-password");
        FluentConfiguration configuration = Flyway.configure().dataSource(applicationPool);

        subject.diarySourceTextFlywayConnectionIsolation(application, migration).customize(configuration);

        assertThat(configuration.getDataSource()).isInstanceOfSatisfying(SimpleDriverDataSource.class, source -> {
            assertThat(source.getUrl()).isEqualTo("jdbc:postgresql://migration-service:5432/harudle");
            assertThat(source.getUsername()).isEqualTo("migration-user");
            assertThat(source.getPassword()).isEqualTo("migration-password");
        });
        verifyNoInteractions(applicationPool);
    }

    @Test
    @DisplayName("명시한 Flyway 사용자만 있으면 나머지 연결 값은 실제 JDBC 설정으로 채운다")
    void fillOnlyMissingMigrationConnectionValues() {
        when(application.getJdbcUrl()).thenReturn("jdbc:postgresql://jdbc-service:5432/harudle");
        when(application.getPassword()).thenReturn("application-password");
        when(migration.getUsername()).thenReturn("migration-user");
        FluentConfiguration configuration = Flyway.configure().dataSource(applicationPool);

        subject.diarySourceTextFlywayConnectionIsolation(application, migration).customize(configuration);

        assertThat(configuration.getDataSource()).isInstanceOfSatisfying(SimpleDriverDataSource.class, source -> {
            assertThat(source.getUrl()).isEqualTo("jdbc:postgresql://jdbc-service:5432/harudle");
            assertThat(source.getUsername()).isEqualTo("migration-user");
            assertThat(source.getPassword()).isEqualTo("application-password");
        });
    }

    @Test
    @DisplayName("실제 Boot 설정 바인딩의 Flyway 주소와 자격 정보도 별도 연결에 보존한다")
    void preserveFlywayPropertyOverridesThroughBootAutoConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class))
                .withUserConfiguration(DiarySourceTextFlywayConfiguration.class)
                .withBean(FlywayMigrationStrategy.class, () -> ignored -> {
                    // Build the actual auto-configuration without connecting or migrating in this unit test.
                })
                .withPropertyValues("spring.datasource.url=jdbc:postgresql://application:5432/harudle",
                        "spring.datasource.username=application-user",
                        "spring.datasource.password=application-password",
                        "spring.flyway.url=jdbc:postgresql://migration:5432/harudle",
                        "spring.flyway.user=migration-user", "spring.flyway.password=migration-password")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DataSource source = context.getBean(Flyway.class).getConfiguration().getDataSource();
                    assertThat(source).isNotSameAs(context.getBean(DataSource.class))
                            .isInstanceOfSatisfying(SimpleDriverDataSource.class, migrationSource -> {
                                assertThat(migrationSource.getUrl())
                                        .isEqualTo("jdbc:postgresql://migration:5432/harudle");
                                assertThat(migrationSource.getUsername()).isEqualTo("migration-user");
                                assertThat(migrationSource.getPassword()).isEqualTo("migration-password");
                            });
                });
    }

    @Test
    @DisplayName("Flyway를 비활성화하면 전용 연결 설정도 등록하지 않고 일반 데이터소스만 시작한다")
    void disableMigrationConnectionConfigurationAlongWithFlyway() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class))
                .withUserConfiguration(DiarySourceTextFlywayConfiguration.class)
                .withPropertyValues("spring.flyway.enabled=false",
                        "spring.datasource.url=jdbc:postgresql://application:5432/harudle",
                        "spring.datasource.username=application-user",
                        "spring.datasource.password=application-password")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(DataSource.class)
                        .doesNotHaveBean(Flyway.class).doesNotHaveBean(FlywayConfigurationCustomizer.class)
                        .doesNotHaveBean(FlywayConnectionDetails.class));
    }
}
