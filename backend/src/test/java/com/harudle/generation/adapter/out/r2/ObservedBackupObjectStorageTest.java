package com.harudle.generation.adapter.out.r2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.harudle.generation.diary.service.port.BackupObjectStorage;
import com.harudle.generation.diary.service.port.BackupStorageException;
import com.harudle.generation.diary.service.port.ImageLookupBudget;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import com.harudle.observability.RequestOperationTimings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class ObservedBackupObjectStorageTest {

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void keepsMissingSeparateFromFailureAndPassesTheSameBudget() {
        var request = new MockHttpServletRequest();
        RequestOperationTimings.start(request);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        BackupObjectStorage delegate = mock(BackupObjectStorage.class);
        ImageLookupBudget budget = ImageLookupBudget.unlimited();
        when(delegate.findMetadata("private-key", budget)).thenReturn(Optional.empty());
        var failure = new BackupStorageException(BackupStorageException.FailureType.AUTHORIZATION_ERROR,
                new IllegalStateException("private-error"));
        when(delegate.findMetadata("denied-key")).thenThrow(failure);
        var registry = new SimpleMeterRegistry();
        try {
            var storage = new ObservedBackupObjectStorage(delegate, registry);
            assertThat(storage.findMetadata("private-key", budget)).isEmpty();
            verify(delegate).findMetadata("private-key", budget);
            assertThatThrownBy(() -> storage.findMetadata("denied-key")).isSameAs(failure);
            assertThat(RequestOperationTimings.finish(request)).containsEntry("r2HeadCount", 2)
                    .containsEntry("r2HeadMissingCount", 1).containsEntry("r2HeadFailureCount", 1);
            assertThat(registry.get("harudle.r2.operation.calls")
                    .tags("operation", "head_object", "result", "missing", "failureType", "NONE")
                    .counter().count()).isEqualTo(1);
            assertThat(registry.get("harudle.r2.operation.calls")
                    .tags("operation", "head_object", "result", "failure", "failureType", "AUTHORIZATION_ERROR")
                    .counter().count()).isEqualTo(1);
            assertThat(registry.getMeters()).allSatisfy(meter ->
                    assertThat(meter.getId().getTags().toString()).doesNotContain("private-", "denied-key"));
        } finally {
            registry.close();
        }
    }

    @Test
    void backgroundSigningStillRecordsGlobalMetricsWithoutAnHttpRequest() {
        BackupObjectStorage delegate = mock(BackupObjectStorage.class);
        var expected = new ImageAccessUrl(URI.create("https://example.invalid/image"), Instant.now());
        when(delegate.createAccessUrl("private-key")).thenReturn(expected);
        var registry = new SimpleMeterRegistry();
        try {
            assertThat(new ObservedBackupObjectStorage(delegate, registry).createAccessUrl("private-key"))
                    .isSameAs(expected);
            assertThat(registry.get("harudle.r2.operation.duration")
                    .tag("operation", "presign_get_object").timer().count()).isEqualTo(1);
        } finally {
            registry.close();
        }
    }
}
