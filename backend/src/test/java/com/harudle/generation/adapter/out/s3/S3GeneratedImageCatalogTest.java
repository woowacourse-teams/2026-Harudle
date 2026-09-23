package com.harudle.generation.adapter.out.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.config.S3StorageProperties;
import com.harudle.generation.diary.service.port.ImageStorageException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

class S3GeneratedImageCatalogTest {
    private final S3Client client = mock(S3Client.class);
    private final S3StorageProperties properties = new S3StorageProperties("bucket", "ap-northeast-2",
            "/generated/", DataSize.ofMegabytes(20), Duration.ofMinutes(15));
    private final ImageObjectKeyFactory keys = new ImageObjectKeyFactory(properties);
    private final S3GeneratedImageCatalog catalog = new S3GeneratedImageCatalog(client, properties, keys,
            new S3FailureReporter(new S3ExceptionTranslator(), mock(ExternalApiLogger.class)));

    @Test
    void listsOnlyRecognizedImagesAndPassesPaginationToken() {
        UUID id = UUID.randomUUID();
        String key = keys.create(id, MediaType.IMAGE_PNG);
        String legacy = "generated/" + id + "/image.jpg";
        Instant time = Instant.now();
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(object(key, time), object(legacy, time), object("generated/reference.png", time),
                        object("generated-other/" + id + "/image.png", time), object(key, null))
                .isTruncated(true).nextContinuationToken("next").build());

        var page = catalog.list("previous");

        assertThat(page.images()).extracting(image -> image.objectKey()).containsExactly(key, legacy);
        assertThat(page.images()).allMatch(image -> image.generationId().equals(id));
        assertThat(page.nextToken()).isEqualTo("next");
        var request = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(client).listObjectsV2(request.capture());
        assertThat(request.getValue().prefix()).isEqualTo("generated/");
        assertThat(request.getValue().bucket()).isEqualTo("bucket");
        assertThat(request.getValue().continuationToken()).isEqualTo("previous");
        assertThat(request.getValue().maxKeys()).isEqualTo(100);
    }

    @Test
    void finishesAtLastPageAndTranslatesProviderFailure() {
        var cause = SdkClientException.builder().message("connection failure").build();
        when(client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().isTruncated(false).build()).thenThrow(cause);
        assertThat(catalog.list(null).nextToken()).isNull();
        assertThatThrownBy(() -> catalog.list(null)).isInstanceOf(ImageStorageException.class).hasCause(cause);
    }

    private S3Object object(String key, Instant time) {
        return S3Object.builder().key(key).lastModified(time).build();
    }
}
