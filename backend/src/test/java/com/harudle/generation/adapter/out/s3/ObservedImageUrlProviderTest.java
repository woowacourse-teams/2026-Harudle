package com.harudle.generation.adapter.out.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.config.S3StorageProperties;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.ImageUrlProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

class ObservedImageUrlProviderTest {

    @Test
    void exportsSigningFailureWithBoundedTypeAndWithoutObjectKey() {
        String objectKey = "private/image.png";
        ImageUrlProvider delegate = mock(ImageUrlProvider.class);
        SdkClientException cause = SdkClientException.builder().message("presigner unavailable").build();
        when(delegate.createAccessUrl(objectKey))
                .thenThrow(new ImageStorageException("URL 발급 실패", cause));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            ObservedImageUrlProvider provider = new ObservedImageUrlProvider(delegate, registry);

            assertThatThrownBy(() -> provider.createAccessUrl(objectKey))
                    .isInstanceOf(ImageStorageException.class);
            assertThat(registry.get("harudle.s3.url.signs")
                    .tags("result", "failure", "failureType", "CONFIGURATION_ERROR")
                    .counter().count()).isEqualTo(1);
            assertThat(registry.getMeters()).allSatisfy(meter ->
                    assertThat(meter.getId().getTags().toString()).doesNotContain(objectKey));
        } finally {
            registry.close();
        }
    }

    @Test
    void presignerIllegalStateFailureKeepsConfigurationClassificationInMetric() {
        String objectKey = "private/image.png";
        S3Presigner presigner = mock(S3Presigner.class);
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                .thenThrow(new IllegalStateException("presigner unavailable"));
        S3FailureReporter reporter = new S3FailureReporter(
                new S3ExceptionTranslator(), mock(ExternalApiLogger.class));
        S3ImageUrlProvider delegate = new S3ImageUrlProvider(presigner, properties(), reporter);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            ObservedImageUrlProvider provider = new ObservedImageUrlProvider(delegate, registry);

            ImageStorageException failure = catchThrowableOfType(
                    () -> provider.createAccessUrl(objectKey), ImageStorageException.class);

            assertThat(failure.diagnosticType())
                    .isEqualTo(ImageStorageException.DiagnosticType.CONFIGURATION_ERROR);
            assertThat(registry.get("harudle.s3.url.signs")
                    .tags("result", "failure", "failureType", "CONFIGURATION_ERROR")
                    .counter().count()).isEqualTo(1);
        } finally {
            registry.close();
        }
    }

    @Test
    void validationAndPreparationFailuresAreNotConfigurationFailures() {
        S3FailureReporter reporter = new S3FailureReporter(
                new S3ExceptionTranslator(), mock(ExternalApiLogger.class));
        S3ImageUrlProvider delegate = new S3ImageUrlProvider(mock(S3Presigner.class), properties(), reporter);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            ObservedImageUrlProvider provider = new ObservedImageUrlProvider(delegate, registry);

            assertThatThrownBy(() -> provider.createAccessUrl(" "))
                    .isInstanceOf(ImageStorageException.class);
            assertThat(registry.get("harudle.s3.url.signs")
                    .tags("result", "failure", "failureType", "REQUEST_VALIDATION_ERROR")
                    .counter().count()).isEqualTo(1);

            ImageUrlProvider preparationFailure = ignored -> {
                throw reporter.reportInternalFailure(
                        "presign_get_object", "접근 URL 발급", null,
                        "REQUEST_PREPARATION_ERROR", new IllegalStateException("request unavailable"));
            };
            ObservedImageUrlProvider preparingProvider = new ObservedImageUrlProvider(preparationFailure, registry);
            assertThatThrownBy(() -> preparingProvider.createAccessUrl("private/image.png"))
                    .isInstanceOf(ImageStorageException.class);
            assertThat(registry.get("harudle.s3.url.signs")
                    .tags("result", "failure", "failureType", "REQUEST_PREPARATION_ERROR")
                    .counter().count()).isEqualTo(1);
        } finally {
            registry.close();
        }
    }

    private static S3StorageProperties properties() {
        return new S3StorageProperties(
                "test-bucket", "ap-northeast-2", "generated/diary-images",
                DataSize.ofMegabytes(20), Duration.ofMinutes(15));
    }
}
