package com.harudle.admin.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.ImageRecoveryService;
import com.harudle.generation.diary.service.dto.ImageRecoveryResult;
import com.harudle.generation.diary.service.exception.ImageRecoveryException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

class AdminR2ImageRecoveryServiceTest {
    private final DiaryGenerationRepository generations = mock(DiaryGenerationRepository.class);
    private final ImageRecoveryService recovery = mock(ImageRecoveryService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ImageRecoveryService> provider = mock(ObjectProvider.class);
    private final RecoveryExecutionGate gate = spy(new RecoveryExecutionGate(Duration.ZERO));
    private AdminR2ImageRecoveryService service;

    @BeforeEach
    void setUp() {
        when(provider.getIfAvailable()).thenReturn(recovery);
        when(recovery.environment()).thenReturn("dev");
        service = new AdminR2ImageRecoveryService(generations, provider, gate);
    }

    @Test
    void keepsGenerationDataAndUsesExistingRecoveryGate() {
        UUID id = UUID.randomUUID();
        var generation = savedGeneration(id, "key");
        when(recovery.recover("key", false)).thenReturn(result("RESTORED", "key"));
        var batch = service.recover("dev", List.of(id), false);
        assertThat(batch.results().getFirst().status()).isEqualTo("RESTORED");
        verify(gate).execute(any());
        verify(generation).getImageObjectKey();
        verify(generation).getStatus();
        verifyNoMoreInteractions(generation);
        verify(generations).findById(id);
        verifyNoMoreInteractions(generations);
    }

    @Test
    void dryRunDoesNotUseWriteExecutionGate() {
        UUID id = UUID.randomUUID();
        savedGeneration(id, "key");
        when(recovery.recover("key", true)).thenReturn(result("WOULD_RESTORE", "key"));
        assertThat(service.recover("dev", List.of(id), true).results().getFirst().status()).isEqualTo("WOULD_RESTORE");
        verifyNoInteractions(gate);
    }

    @Test
    void continuesAfterPerTargetFailureAndReportsEachOutcome() {
        UUID failed = UUID.randomUUID();
        UUID restored = UUID.randomUUID();
        UUID skipped = UUID.randomUUID();
        savedGeneration(failed, "missing-backup");
        savedGeneration(restored, "restored");
        savedGeneration(skipped, "existing");
        when(recovery.recover("missing-backup", false)).thenThrow(new ImageRecoveryException(
                ImageRecoveryException.Reason.BACKUP_NOT_FOUND));
        when(recovery.recover("restored", false)).thenReturn(result("RESTORED", "restored"));
        when(recovery.recover("existing", false)).thenReturn(result("ALREADY_EXISTS", "existing"));
        var results = service.recover("dev", List.of(failed, restored, skipped), false).results();
        assertThat(results).extracting(AdminR2ImageRecoveryService.TargetResult::status)
                .containsExactly("FAILED", "RESTORED", "ALREADY_EXISTS");
        assertThat(results.getFirst().failureCode()).isEqualTo("BACKUP_NOT_FOUND");
        assertThat(results.getFirst().recovery()).isNull();
    }

    @Test
    void checksGenerationStatusAndExistenceWithoutStoryboardOrPrompts() {
        UUID absent = UUID.randomUUID();
        UUID processing = UUID.randomUUID();
        when(generations.findById(absent)).thenReturn(Optional.empty());
        var generation = savedGeneration(processing, "key");
        when(generation.getStatus()).thenReturn(GenerationStatus.PROCESSING);
        var results = service.recover("dev", List.of(absent, processing), true).results();
        assertThat(results).extracting(AdminR2ImageRecoveryService.TargetResult::failureCode)
                .containsExactly("GENERATION_NOT_FOUND", "GENERATION_NOT_RECOVERABLE");
        verify(recovery, never()).recover(anyString(), anyBoolean());
    }

    @Test
    void rejectsWrongEnvironmentBeforeReadingTargets() {
        assertThatThrownBy(() -> service.recover("prod", List.of(UUID.randomUUID()), false))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode().value()).isEqualTo(409);
        verifyNoInteractions(generations, gate);
    }

    @Test
    void rejectsDuplicateAndOversizedScope() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.recover("dev", List.of(id, id), false))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.recover("dev", java.util.stream.IntStream.range(0, 101)
                .mapToObj(index -> UUID.randomUUID()).toList(), false)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(generations, recovery, gate);
    }

    @Test
    void disabledStorageReportsUnavailable() {
        when(provider.getIfAvailable()).thenReturn(null);
        assertThatThrownBy(() -> service.recover("dev", List.of(UUID.randomUUID()), true))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode().value()).isEqualTo(503);
        verifyNoInteractions(generations, gate);
    }

    @Test
    void interruptionAbortsRemainingTargetsAndAllowsSameRequestToBeRetried() {
        UUID id = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "중단"))
                .when(gate).execute(any());
        assertThatThrownBy(() -> service.recover("dev", List.of(id, second), false))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(generations);
    }

    private DiaryGeneration savedGeneration(UUID id, String key) {
        var generation = mock(DiaryGeneration.class);
        when(generation.getImageObjectKey()).thenReturn(key);
        when(generation.getStatus()).thenReturn(GenerationStatus.SUCCEEDED);
        when(generations.findById(id)).thenReturn(Optional.of(generation));
        return generation;
    }

    private ImageRecoveryResult result(String status, String key) {
        return new ImageRecoveryResult(status, "dev", "s3", "r2", key, key, "image/png", 1,
                "a".repeat(64), List.of(), false, false, Instant.EPOCH);
    }
}
