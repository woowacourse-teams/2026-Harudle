package com.harudle.generation.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.generation.diary.service.dto.CompletedDiaryGeneration;
import com.harudle.generation.diary.service.dto.GenerateDiaryImageCommand;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class ObservedDiaryGenerationExecutorTest {

    @Test
    void successfulExecutionRecordsOneResultAndRestoresGenerationId() {
        UUID generationId = UUID.randomUUID();
        CompletedDiaryGeneration expected = new CompletedDiaryGeneration(
                generationId, "제목", "generated/image.png", Instant.now()
        );
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            DiaryGenerationExecutor delegate = new StubExecutor() {
                @Override
                public CompletedDiaryGeneration generate(GenerateDiaryImageCommand command, UUID id) {
                    assertThat(MDC.get("generationId")).isEqualTo(generationId.toString());
                    return expected;
                }
            };
            ObservedDiaryGenerationExecutor observed = new ObservedDiaryGenerationExecutor(delegate, registry);
            MDC.put("generationId", "outer-generation");
            try {
                assertThat(observed.generate(command(), generationId)).isSameAs(expected);
                assertThat(MDC.get("generationId")).isEqualTo("outer-generation");
            } finally {
                MDC.remove("generationId");
            }
            assertThat(registry.get("harudle.generation.executions")
                    .tag("result", "returned").counter().count()).isEqualTo(1);
            assertThat(registry.get("harudle.generation.duration")
                    .tag("result", "returned").timer().count()).isEqualTo(1);
            assertThat(registry.getMeters()).allSatisfy(meter ->
                    assertThat(meter.getId().getTag("generationId")).isNull());
        } finally {
            registry.close();
        }
    }

    @Test
    void thrownExecutionRecordsFailureWithoutLeakingGenerationId() {
        UUID generationId = UUID.randomUUID();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            DiaryGenerationExecutor delegate = new StubExecutor() {
                @Override
                public CompletedDiaryGeneration generate(GenerateDiaryImageCommand command, UUID id) {
                    assertThat(MDC.get("generationId")).isEqualTo(generationId.toString());
                    throw new IllegalStateException("generation failed");
                }
            };
            ObservedDiaryGenerationExecutor observed = new ObservedDiaryGenerationExecutor(delegate, registry);
            assertThatThrownBy(() -> observed.generate(command(), generationId))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(MDC.get("generationId")).isNull();
            assertThat(registry.get("harudle.generation.executions")
                    .tag("result", "threw").counter().count()).isEqualTo(1);
            assertThat(registry.get("harudle.generation.duration")
                    .tag("result", "threw").timer().count()).isEqualTo(1);
        } finally {
            registry.close();
        }
    }

    private static GenerateDiaryImageCommand command() {
        return new GenerateDiaryImageCommand(
                UUID.randomUUID(), UUID.randomUUID(), LocalDate.now(), "일기", UUID.randomUUID()
        );
    }

    private abstract static class StubExecutor implements DiaryGenerationExecutor {
        @Override
        public boolean isConfigured() {
            return true;
        }
    }
}
