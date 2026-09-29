package com.harudle.generation.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.generation.config.ImageIntegrityProperties;
import com.harudle.generation.diary.repository.ImageIntegrityCandidate;
import com.harudle.generation.diary.repository.ImageIntegrityCandidateRepository;
import com.harudle.generation.diary.service.port.ImageStorage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class ImageIntegrityMonitorTest {

    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private static final Instant CUTOFF = NOW.minus(Duration.ofMinutes(5));
    private static final ImageIntegrityCandidate FIRST = candidate(1);
    private static final ImageIntegrityCandidate SECOND = candidate(2);
    private static final ImageIntegrityCandidate THIRD = candidate(3);

    @Test
    void scansAcrossRunsAndSeparatesMissingFromCheckErrors() {
        ImageIntegrityCandidateRepository candidates = mock(ImageIntegrityCandidateRepository.class);
        ImageStorage storage = mock(ImageStorage.class);
        when(candidates.findNext(isNull(), eq(CUTOFF), eq(2))).thenReturn(List.of(FIRST, SECOND));
        when(candidates.findNext(eq(SECOND.generationId()), eq(CUTOFF), eq(2)))
                .thenReturn(List.of(THIRD));
        when(candidates.findNext(eq(THIRD.generationId()), eq(CUTOFF), eq(2)))
                .thenReturn(List.of());
        when(storage.exists(FIRST.imageObjectKey())).thenReturn(true);
        when(storage.exists(SECOND.imageObjectKey())).thenReturn(false);
        when(candidates.isStillVisible(SECOND, CUTOFF)).thenReturn(true);
        when(storage.exists(THIRD.imageObjectKey())).thenThrow(new IllegalStateException("S3 unavailable"));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            ImageIntegrityMonitor monitor = new ImageIntegrityMonitor(
                    candidates, storage, registry, Clock.fixed(NOW, ZoneOffset.UTC), properties(2));
            MDC.put("generationId", "outer-generation");
            try {
                monitor.checkVisibleImages();
                assertThat(registry.get("harudle.image.integrity.last.completed").gauge().value()).isZero();
                monitor.checkVisibleImages();
                assertThat(MDC.get("generationId")).isEqualTo("outer-generation");
            } finally {
                MDC.remove("generationId");
            }

            assertCount(registry, "harudle.image.integrity.checks", "present", 1);
            assertCount(registry, "harudle.image.integrity.checks", "missing", 1);
            assertCount(registry, "harudle.image.integrity.checks", "error", 1);
            assertCount(registry, "harudle.image.integrity.runs", "partial", 1);
            assertCount(registry, "harudle.image.integrity.runs", "complete", 1);
            assertThat(registry.get("harudle.image.integrity.last.completed").gauge().value())
                    .isEqualTo(NOW.getEpochSecond());
            assertThat(registry.get("harudle.image.integrity.last.sweep.size").gauge().value())
                    .isEqualTo(3);
            assertThat(registry.get("harudle.image.integrity.last.sweep.missing").gauge().value())
                    .isEqualTo(1);
            assertThat(registry.get("harudle.image.integrity.last.sweep.errors").gauge().value())
                    .isEqualTo(1);
            assertThat(registry.getMeters()).allSatisfy(meter ->
                    assertThat(meter.getId().getTag("generationId")).isNull());
        } finally {
            registry.close();
        }
    }

    @Test
    void ignoresCandidateThatBecameInvisibleBeforeMissingWasConfirmed() {
        ImageIntegrityCandidateRepository candidates = mock(ImageIntegrityCandidateRepository.class);
        ImageStorage storage = mock(ImageStorage.class);
        when(candidates.findNext(isNull(), eq(CUTOFF), eq(2))).thenReturn(List.of(FIRST));
        when(candidates.findNext(eq(FIRST.generationId()), eq(CUTOFF), eq(2)))
                .thenReturn(List.of());
        when(storage.exists(FIRST.imageObjectKey())).thenReturn(false);
        when(candidates.isStillVisible(FIRST, CUTOFF)).thenReturn(false);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            ImageIntegrityMonitor monitor = new ImageIntegrityMonitor(
                    candidates, storage, registry, Clock.fixed(NOW, ZoneOffset.UTC), properties(2));
            monitor.checkVisibleImages();

            assertCount(registry, "harudle.image.integrity.checks", "ineligible", 1);
            assertThat(registry.find("harudle.image.integrity.checks")
                    .tag("result", "missing").counter()).isNull();
            verify(candidates).isStillVisible(FIRST, CUTOFF);
        } finally {
            registry.close();
        }
    }

    @Test
    void keepsCursorAfterDatabaseQueryFailure() {
        ImageIntegrityCandidateRepository candidates = mock(ImageIntegrityCandidateRepository.class);
        ImageStorage storage = mock(ImageStorage.class);
        when(candidates.findNext(isNull(), eq(CUTOFF), eq(1))).thenReturn(List.of(FIRST));
        when(candidates.findNext(eq(FIRST.generationId()), eq(CUTOFF), eq(1)))
                .thenThrow(new IllegalStateException("DB unavailable"))
                .thenReturn(List.of());
        when(storage.exists(FIRST.imageObjectKey())).thenReturn(true);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            ImageIntegrityMonitor monitor = new ImageIntegrityMonitor(
                    candidates, storage, registry, Clock.fixed(NOW, ZoneOffset.UTC), properties(1));
            monitor.checkVisibleImages();
            monitor.checkVisibleImages();
            monitor.checkVisibleImages();

            assertCount(registry, "harudle.image.integrity.runs", "partial", 1);
            assertCount(registry, "harudle.image.integrity.runs", "query_error", 1);
            assertCount(registry, "harudle.image.integrity.runs", "complete", 1);
            assertCount(registry, "harudle.image.integrity.checks", "present", 1);
        } finally {
            registry.close();
        }
    }

    private static ImageIntegrityProperties properties(int maxChecksPerRun) {
        return new ImageIntegrityProperties(true, Duration.ofMinutes(1), Duration.ofMinutes(1),
                2, maxChecksPerRun, Duration.ofMinutes(5), Duration.ofMinutes(1));
    }

    private static ImageIntegrityCandidate candidate(long id) {
        return new ImageIntegrityCandidate(new UUID(0, id), "generated/image-" + id + ".png");
    }

    private static void assertCount(SimpleMeterRegistry registry, String name, String result, int expected) {
        assertThat(registry.get(name).tag("result", result).counter().count()).isEqualTo(expected);
    }
}
