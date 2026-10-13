package com.harudle.generation.adapter.out.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.ImageLookupBudget;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import software.amazon.awssdk.services.s3.model.S3Exception;

class ObservedImageStorageTest {

    @Test
    void passesSameBudgetThroughMetricsWrapper() {
        ImageStorage delegate = mock(ImageStorage.class);
        ImageLookupBudget budget = ImageLookupBudget.unlimited();
        when(delegate.exists("image.png", budget)).thenReturn(true);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            assertThat(new ObservedImageStorage(delegate, registry).exists("image.png", budget)).isTrue();
            verify(delegate).exists("image.png", budget);
        } finally {
            registry.close();
        }
    }

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
                    .tags("operation", "head_object", "result", "missing", "failureType", "NONE")
                    .counter().count()).isEqualTo(1);
            assertThat(registry.get("harudle.s3.operation.calls")
                    .tags("operation", "delete_object", "result", "failure", "failureType", "OTHER")
                    .counter().count()).isEqualTo(1);
            assertThat(registry.getMeters()).allSatisfy(meter ->
                    assertThat(meter.getId().getTags().toString()).doesNotContain("private/image.png"));
        } finally {
            registry.close();
        }
    }

    @Test
    void classifiesWrappedS3AuthorizationFailureWithoutObjectKeyTag() {
        ImageStorage delegate = mock(ImageStorage.class);
        String objectKey = "private/image.png";
        Exception cause = S3Exception.builder().statusCode(403).build();
        when(delegate.load(objectKey)).thenThrow(new ImageStorageException("이미지 조회 실패", cause));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            ObservedImageStorage storage = new ObservedImageStorage(delegate, registry);

            assertThatThrownBy(() -> storage.load(objectKey)).isInstanceOf(ImageStorageException.class);
            assertThat(registry.get("harudle.s3.operation.calls")
                    .tags("operation", "get_object", "result", "failure",
                            "failureType", "AUTHORIZATION_ERROR")
                    .counter().count()).isEqualTo(1);
            assertThat(registry.getMeters()).allSatisfy(meter ->
                    assertThat(meter.getId().getTags().toString()).doesNotContain(objectKey));
        } finally {
            registry.close();
        }
    }

    @Test
    void observesBothOptimizedImageRecoveryOperations() {
        ImageStorage delegate = mock(ImageStorage.class);
        String detailKey = "private/detail.webp";
        GeneratedImage image = new GeneratedImage(new ByteArrayResource(new byte[]{1}), MediaType.IMAGE_PNG);
        when(delegate.restoreOptimizedIfMissing(detailKey, image)).thenReturn(true);
        when(delegate.restoreMissingThumbnail(detailKey)).thenReturn(false);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            ObservedImageStorage storage = new ObservedImageStorage(delegate, registry);

            assertThat(storage.restoreOptimizedIfMissing(detailKey, image)).isTrue();
            assertThat(storage.restoreMissingThumbnail(detailKey)).isFalse();
            assertThat(registry.get("harudle.s3.operation.calls")
                    .tags("operation", "restore_optimized", "result", "restored", "failureType", "NONE")
                    .counter().count()).isEqualTo(1);
            assertThat(registry.get("harudle.s3.operation.calls")
                    .tags("operation", "restore_thumbnail", "result", "already_present", "failureType", "NONE")
                    .counter().count()).isEqualTo(1);
            assertThat(registry.getMeters()).allSatisfy(meter ->
                    assertThat(meter.getId().getTags().toString()).doesNotContain(detailKey));
        } finally {
            registry.close();
        }
    }
}
