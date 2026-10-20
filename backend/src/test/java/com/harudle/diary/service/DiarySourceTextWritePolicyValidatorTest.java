package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.support.SpringApplicationJsonEnvironmentPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

class DiarySourceTextWritePolicyValidatorTest {

    private static final String ENABLED_PROPERTY = "harudle.diary.source-text-encryption.new-writes-enabled";
    private static final String RELEASE_MODE = "DIARY_SOURCE_TEXT_RELEASE_MODE";

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(DiarySourceTextWriteConfiguration.class);

    @ParameterizedTest
    @CsvSource({"reader,false", "writer,true"})
    @DisplayName("prod는 실제 환경 변수의 배포 모드와 바인딩된 작성 설정이 같으면 시작한다")
    void acceptMatchingProductionWritePolicy(String mode, boolean enabled) {
        MockEnvironment environment = productionEnvironment(mode)
                .withProperty(ENABLED_PROPERTY, Boolean.toString(enabled));

        context.withInitializer(application -> application.setEnvironment(environment))
                .run(application -> assertThat(application).hasNotFailed());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"invalid-sensitive-mode", "READER", " reader"})
    @DisplayName("prod는 실제 배포 모드가 없거나 잘못되면 입력값을 노출하지 않고 시작을 거절한다")
    void rejectMissingOrUnknownProductionMode(String mode) {
        MockEnvironment environment = productionEnvironment(mode).withProperty(ENABLED_PROPERTY, "false");

        context.withInitializer(application -> application.setEnvironment(environment))
                .run(application -> {
                    assertThat(application).hasFailed().getFailure()
                            .hasRootCauseMessage("Diary source text write policy validation failed.");
                    if (mode != null && !mode.isEmpty()) {
                        assertThat(application.getStartupFailure().getMessage()).doesNotContain(mode);
                    }
                });
    }

    @ParameterizedTest
    @CsvSource({"reader,true", "writer,false"})
    @DisplayName("prod는 배포 모드와 작성 허용 설정이 다르면 시작을 거절한다")
    void rejectMismatchingProductionWritePolicy(String mode, boolean enabled) {
        MockEnvironment environment = productionEnvironment(mode)
                .withProperty(ENABLED_PROPERTY, Boolean.toString(enabled));

        context.withInitializer(application -> application.setEnvironment(environment))
                .run(application -> assertThat(application).hasFailed().getFailure()
                        .hasRootCauseMessage("Diary source text write policy validation failed."));
    }

    @Test
    @DisplayName("상위 시스템 속성으로 reader의 작성 설정과 배포 모드를 바꿔도 시작을 거절한다")
    void rejectSystemPropertyOverrideOfReaderPolicy() {
        MockEnvironment environment = productionEnvironment("reader").withProperty(ENABLED_PROPERTY, "false");
        environment.getPropertySources().addFirst(new MapPropertySource("systemProperties", Map.of(
                ENABLED_PROPERTY, "true", RELEASE_MODE, "writer"
        )));
        assertThat(environment.getProperty(ENABLED_PROPERTY)).isEqualTo("true");
        assertThat(environment.getProperty(RELEASE_MODE)).isEqualTo("writer");

        context.withInitializer(application -> application.setEnvironment(environment))
                .run(application -> assertThat(application).hasFailed().getFailure()
                        .hasRootCauseMessage("Diary source text write policy validation failed."));
    }

    @Test
    @DisplayName("Spring JSON으로 reader의 작성 설정과 배포 모드를 바꿔도 시작을 거절한다")
    void rejectSpringJsonOverrideOfReaderPolicy() {
        MockEnvironment environment = productionEnvironment("reader").withProperty(ENABLED_PROPERTY, "false")
                .withProperty("SPRING_APPLICATION_JSON", """
                        {"harudle":{"diary":{"source-text-encryption":{"new-writes-enabled":true}}},
                         "DIARY_SOURCE_TEXT_RELEASE_MODE":"writer"}
                        """);
        new SpringApplicationJsonEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication(DiarySourceTextWriteConfiguration.class));
        assertThat(environment.getProperty(ENABLED_PROPERTY)).isEqualTo("true");
        assertThat(environment.getProperty(RELEASE_MODE)).isEqualTo("writer");

        context.withInitializer(application -> application.setEnvironment(environment))
                .run(application -> assertThat(application).hasFailed().getFailure()
                        .hasRootCauseMessage("Diary source text write policy validation failed."));
    }

    @Test
    @DisplayName("prod는 실제 모드가 없으면 상위 설정의 모드 선언만으로 시작하지 않는다")
    void rejectPropertyOnlyProductionMode() {
        MockEnvironment environment = productionEnvironment(null)
                .withProperty(RELEASE_MODE, "writer").withProperty(ENABLED_PROPERTY, "true");

        context.withInitializer(application -> application.setEnvironment(environment))
                .run(application -> assertThat(application).hasFailed().getFailure()
                        .hasRootCauseMessage("Diary source text write policy validation failed."));
    }

    @Test
    @DisplayName("reader 배포는 상위 설정으로 prod를 local로 바꾸고 작성을 열어도 시작을 거절한다")
    void rejectReaderOverrideEvenWithNonProductionProfile() {
        MockEnvironment environment = productionEnvironment("reader")
                .withProperty(ENABLED_PROPERTY, "false")
                .withProperty("SPRING_APPLICATION_JSON", """
                        {"spring":{"profiles":{"active":"local"}},
                         "harudle":{"diary":{"source-text-encryption":{"new-writes-enabled":true}}}}
                        """);
        new SpringApplicationJsonEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication(DiarySourceTextWriteConfiguration.class));
        environment.setActiveProfiles(environment.getRequiredProperty("spring.profiles.active"));
        assertThat(environment.matchesProfiles("prod")).isFalse();
        assertThat(environment.getProperty(ENABLED_PROPERTY)).isEqualTo("true");

        context.withInitializer(application -> application.setEnvironment(environment))
                .run(application -> assertThat(application).hasFailed().getFailure()
                        .hasRootCauseMessage("Diary source text write policy validation failed."));
    }

    @Test
    @DisplayName("local과 test 프로필은 배포 모드 환경 변수 없이 시작할 수 있다")
    void allowLocalAndTestProfilesWithoutReleaseMode() {
        MockEnvironment environment = productionEnvironment(null);
        environment.setActiveProfiles("local", "test");

        context.withInitializer(application -> application.setEnvironment(environment))
                .run(application -> assertThat(application).hasNotFailed());
    }

    private MockEnvironment productionEnvironment(String mode) {
        Map<String, Object> variables = new HashMap<>();
        if (mode != null) {
            variables.put(RELEASE_MODE, mode);
        }
        MockEnvironment environment = new MockEnvironment() {
            @Override
            public Map<String, Object> getSystemEnvironment() {
                return variables;
            }
        };
        environment.setActiveProfiles("prod");
        return environment;
    }
}
