package com.harudle.generation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.harudle.diary.repository.DiaryRepository;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.DiaryGenerationCompletionService;
import com.harudle.generation.diary.service.DiaryGenerationExecutor;
import com.harudle.generation.diary.service.DiaryGenerationWorker;
import com.harudle.generation.diary.service.GenerationLifecycleMetrics;
import com.harudle.generation.diary.service.PendingDiaryGenerationClaimService;
import com.harudle.generation.diary.service.RequestFingerprintGenerator;
import com.harudle.generation.diary.service.port.DiaryImageGenerator;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.storyboard.StoryboardGenerationService;
import com.harudle.generation.prompt.repository.GenerationPromptRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class DiaryGenerationConfigurationTest {

    @Test
    @DisplayName("작업 처리기는 기본으로 꺼져 있고 실행 수와 확인 간격의 기본값을 제공한다")
    void disableWorkerByDefault() {
        try (AnnotationConfigApplicationContext context = createContext(false)) {
            GenerationWorkerProperties properties = context.getBean(GenerationWorkerProperties.class);

            assertThat(properties.enabled()).isFalse();
            assertThat(properties.concurrency()).isEqualTo(1);
            assertThat(properties.pollInterval()).isEqualTo(Duration.ofSeconds(5));
            assertThat(context.getBeansOfType(DiaryGenerationWorker.class)).isEmpty();
            assertThat(context.containsBean("generationWorkerTaskExecutor")).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    @DisplayName("설정한 수만큼 실행 스레드를 만들고 메모리 대기열에는 작업을 쌓지 않는다")
    void configureWorkerConcurrency(int concurrency) {
        try (AnnotationConfigApplicationContext context = createWorkerContext(concurrency, "1h")) {
            context.refresh();
            ThreadPoolTaskExecutor executor = context.getBean("generationWorkerTaskExecutor", ThreadPoolTaskExecutor.class);
            GenerationWorkerProperties properties = context.getBean(GenerationWorkerProperties.class);

            assertThat(properties.enabled()).isTrue();
            assertThat(properties.concurrency()).isEqualTo(concurrency);
            assertThat(properties.pollInterval()).isEqualTo(Duration.ofHours(1));
            assertThat(executor.getCorePoolSize()).isEqualTo(concurrency);
            assertThat(executor.getMaxPoolSize()).isEqualTo(concurrency);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isZero();
            assertThat(context.getBean(DiaryGenerationWorker.class)).isNotNull();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("동시에 실행할 작업 수가 1보다 작으면 시작하지 않는다")
    void rejectInvalidWorkerConcurrency(int concurrency) {
        try (AnnotationConfigApplicationContext context = createWorkerContext(concurrency, "1h")) {
            assertThatThrownBy(context::refresh)
                    .hasRootCauseInstanceOf(BindValidationException.class)
                    .hasStackTraceContaining("동시에 실행할 생성 작업 수는 1 이상이어야 합니다.");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0ms", "-1s", "1ns"})
    @DisplayName("작업 확인 간격이 1ms보다 짧으면 시작하지 않는다")
    void rejectInvalidWorkerPollInterval(String pollInterval) {
        try (AnnotationConfigApplicationContext context = createWorkerContext(1, pollInterval)) {
            assertThatThrownBy(context::refresh)
                    .hasRootCauseInstanceOf(BindValidationException.class)
                    .hasStackTraceContaining("생성 작업 확인 간격은 1ms 이상이어야 합니다.");
        }
    }

    @Test
    @DisplayName("작업 처리기를 켜면 예약 작업 스레드와 분리된 실행 스레드에서 선점한다")
    void scheduleWorkerPolls() throws InterruptedException {
        try (AnnotationConfigApplicationContext context = createWorkerContext(1, "10ms")) {
            registerAdapters(context);
            context.refresh();
            CountDownLatch claimed = new CountDownLatch(1);
            AtomicReference<String> threadName = new AtomicReference<>();
            when(context.getBean(PendingDiaryGenerationClaimService.class).claim(1)).thenAnswer(invocation -> {
                threadName.set(Thread.currentThread().getName());
                claimed.countDown();
                return List.of();
            });

            assertThat(claimed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(threadName.get()).startsWith("generation-worker-");
        }
    }

    @Test
    @DisplayName("어댑터 빈의 등록 순서와 무관하게 생성 서비스를 연결한다")
    void wireGenerationServiceWithAdapters() {
        try (AnnotationConfigApplicationContext context = createContext(true)) {
            DiaryGenerationExecutor executor = context.getBean(DiaryGenerationExecutor.class);

            assertThat(executor.isConfigured()).isTrue();
        }
    }

    @Test
    @DisplayName("어댑터가 없으면 생성 서비스를 안전하게 비활성 상태로 둔다")
    void keepGenerationServiceUnavailableWithoutAdapters() {
        try (AnnotationConfigApplicationContext context = createContext(false)) {
            DiaryGenerationExecutor executor = context.getBean(DiaryGenerationExecutor.class);

            assertThat(executor.isConfigured()).isFalse();
        }
    }

    @Test
    @DisplayName("일부 생성 어댑터만 있으면 불완전한 구성으로 애플리케이션 시작을 중단한다")
    void rejectPartiallyConfiguredAdapters() {
        try (AnnotationConfigApplicationContext context = createBaseContext()) {
            context.registerBean(
                    "storyboardGenerationService",
                    StoryboardGenerationService.class,
                    () -> mock(StoryboardGenerationService.class)
            );

            assertThatThrownBy(context::refresh)
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("AI 생성 어댑터는 모두 함께 구성해야 합니다.");
        }
    }

    @Test
    @DisplayName("스토리보드 조합 서비스가 여러 개면 구성을 임의로 선택하지 않는다")
    void rejectAmbiguousAdapters() {
        try (AnnotationConfigApplicationContext context = createBaseContext()) {
            registerAdapters(context);
            context.registerBean(
                    "secondStoryboardGenerationService",
                    StoryboardGenerationService.class,
                    () -> mock(StoryboardGenerationService.class)
            );

            assertThatThrownBy(context::refresh)
                    .hasRootCauseInstanceOf(NoUniqueBeanDefinitionException.class);
        }
    }

    private AnnotationConfigApplicationContext createContext(boolean registerAdapters) {
        AnnotationConfigApplicationContext context = createBaseContext();
        if (registerAdapters) {
            registerAdapters(context);
        }
        context.refresh();
        return context;
    }

    private AnnotationConfigApplicationContext createWorkerContext(int concurrency, String pollInterval) {
        AnnotationConfigApplicationContext context = createBaseContext();
        TestPropertyValues.of(
                "harudle.generation.worker.enabled=true",
                "harudle.generation.worker.concurrency=" + concurrency,
                "harudle.generation.worker.poll-interval=" + pollInterval
        ).applyTo(context);
        context.registerBean(PendingDiaryGenerationClaimService.class, () -> mock(PendingDiaryGenerationClaimService.class));
        context.registerBean(DiaryRepository.class, () -> mock(DiaryRepository.class));
        return context;
    }

    private AnnotationConfigApplicationContext createBaseContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        TestPropertyValues.of(
                "harudle.generation.lifecycle.processing-timeout=15m",
                "harudle.generation.lifecycle.cleanup-interval=1m"
        ).applyTo(context);
        context.registerBean(
                RequestFingerprintGenerator.class,
                () -> mock(RequestFingerprintGenerator.class)
        );
        context.registerBean(GenerationPromptRepository.class, () -> mock(GenerationPromptRepository.class));
        context.registerBean(DiaryGenerationRepository.class, () -> mock(DiaryGenerationRepository.class));
        context.registerBean(
                DiaryGenerationCompletionService.class,
                () -> mock(DiaryGenerationCompletionService.class)
        );
        context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
        context.registerBean(GenerationLifecycleMetrics.class, () -> mock(GenerationLifecycleMetrics.class));
        context.register(DiaryGenerationConfiguration.class);
        return context;
    }

    private void registerAdapters(AnnotationConfigApplicationContext context) {
        context.registerBean(
                "storyboardGenerationService",
                StoryboardGenerationService.class,
                () -> mock(StoryboardGenerationService.class)
        );
        context.registerBean(
                "diaryImageGenerator",
                DiaryImageGenerator.class,
                () -> mock(DiaryImageGenerator.class)
        );
        context.registerBean("imageStorage", ImageStorage.class, () -> mock(ImageStorage.class));
    }
}
