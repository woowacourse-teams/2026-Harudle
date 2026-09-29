package com.harudle.generation.adapter.out.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.harudle.generation.diary.service.port.ImageStorage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ObservedImageStorageTest {

    @Test
    void distinguishesMissingHeadFromFailedDeleteWithoutObjectKeyTags() {
        ImageStorage delegate = mock(ImageStorage.class);
        when(delegate.exists("private/image.png")).thenReturn(false);
        doThrow(new IllegalStateException("S3 unavailable"))
                .when(delegate).delete("private/image.png");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            ObservedImageStorage storage = new ObservedImageStorage(delegate, registry);

            assertThat(storage.exists("private/image.png")).isFalse();
            assertThatThrownBy(() -> storage.delete("private/image.png"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(registry.get("harudle.s3.operation.calls")
                    .tags("operation", "head_object", "result", "missing")
                    .counter().count()).isEqualTo(1);
            assertThat(registry.get("harudle.s3.operation.calls")
                    .tags("operation", "delete_object", "result", "failure")
                    .counter().count()).isEqualTo(1);
            assertThat(registry.getMeters()).allSatisfy(meter ->
                    assertThat(meter.getId().getTags().toString()).doesNotContain("private/image.png"));
        } finally {
            registry.close();
        }
    }
}
