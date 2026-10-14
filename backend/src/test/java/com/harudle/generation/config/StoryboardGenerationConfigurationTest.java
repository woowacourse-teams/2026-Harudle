package com.harudle.generation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.port.StoryboardGenerator;
import com.harudle.generation.diary.service.port.dto.GeneratedStoryboard;
import com.harudle.generation.diary.service.port.dto.StoryboardGenerationRequest;
import com.harudle.generation.diary.service.storyboard.StoryboardAttemptRunner;
import com.harudle.generation.diary.service.storyboard.StoryboardGenerationService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class StoryboardGenerationConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(StoryboardGenerationConfiguration.class);
    private final StoryboardGenerationRequest request = new StoryboardGenerationRequest("일기", "공통 프롬프트");

    @ParameterizedTest
    @CsvSource({"gemini,qwen", "qwen,gemini"})
    @DisplayName("Bean 이름으로 선택한 메인 실패 후 보조를 호출하고 애플리케이션 종료 시 실행기를 정리한다")
    void selectProvidersAndManageExecutorLifecycle(String primaryName, String fallbackName) {
        var primary = mock(StoryboardGenerator.class);
        var fallback = mock(StoryboardGenerator.class);
        var result = mock(GeneratedStoryboard.class);
        when(primary.generate(any())).thenThrow(new AiGenerationException(AiGenerationErrorType.TIMEOUT, "시간 초과"));
        when(fallback.generate(any())).thenAnswer(invocation -> {
            assertThat(Thread.currentThread().isVirtual()).isTrue();
            assertThat(Thread.currentThread().getName()).startsWith("storyboard-attempt-");
            assertThat(MDC.get("generationId")).isEqualTo("test-generation");
            return result;
        });
        var executor = new AtomicReference<ExecutorService>();
        MDC.put("generationId", "test-generation");
        try {
            contextRunner.withPropertyValues("harudle.generation.adapters.enabled=true",
                            "harudle.generation.storyboard.provider=" + primaryName,
                            "harudle.generation.storyboard.fallback-provider=" + fallbackName)
                    .withBean(primaryName + "StoryboardGenerator", StoryboardGenerator.class, () -> primary)
                    .withBean(fallbackName + "StoryboardGenerator", StoryboardGenerator.class, () -> fallback)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).hasSingleBean(StoryboardGenerationService.class);
                        assertThat(context).hasSingleBean(StoryboardAttemptRunner.class);
                        executor.set(context.getBean("storyboardAttemptExecutor", ExecutorService.class));

                        assertThat(context.getBean(StoryboardGenerationService.class).generate(request)).isSameAs(result);

                        verify(primary).generate(any());
                        verify(fallback).generate(any());
                        assertThat(MDC.get("generationId")).isEqualTo("test-generation");
                        assertThat(executor.get().isShutdown()).isFalse();
                    });
        } finally {
            MDC.remove("generationId");
        }
        assertThat(executor.get().isShutdown()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"gemini", "qwen"})
    @DisplayName("보조가 비활성화되면 등록된 다른 Provider를 호출하지 않고 메인만 호출한다")
    void preserveSingleProviderPath(String primaryName) {
        var primary = mock(StoryboardGenerator.class);
        var unused = mock(StoryboardGenerator.class);
        var result = mock(GeneratedStoryboard.class);
        when(primary.generate(any())).thenReturn(result);
        String unusedName = primaryName.equals("gemini") ? "qwen" : "gemini";
        contextRunner.withPropertyValues("harudle.generation.adapters.enabled=true",
                        "harudle.generation.storyboard.provider=" + primaryName)
                .withBean(primaryName + "StoryboardGenerator", StoryboardGenerator.class, () -> primary)
                .withBean(unusedName + "StoryboardGenerator", StoryboardGenerator.class, () -> unused)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(StoryboardGenerationService.class).generate(request)).isSameAs(result);
                    verify(primary).generate(any());
                    verify(unused, never()).generate(any());
                });
    }

    @Test
    @DisplayName("선택한 보조의 이름에 해당하는 구현체가 없으면 시작 시 거절한다")
    void rejectMissingSelectedFallback() {
        contextRunner.withPropertyValues("harudle.generation.adapters.enabled=true",
                        "harudle.generation.storyboard.fallback-provider=qwen")
                .withBean("geminiStoryboardGenerator", StoryboardGenerator.class, () -> mock(StoryboardGenerator.class))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("선택한 스토리보드 Provider 구현체가 없습니다: qwen");
                });
    }

    @Test
    @DisplayName("어댑터가 꺼져 있으면 조합 서비스와 실행기를 초기화하지 않는다")
    void keepOrchestrationDisabled() {
        contextRunner.withPropertyValues("harudle.generation.storyboard.fallback-provider=qwen").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(StoryboardGenerationService.class);
            assertThat(context).doesNotHaveBean(StoryboardAttemptRunner.class);
            assertThat(context).doesNotHaveBean("storyboardAttemptExecutor");
        });
    }
}
