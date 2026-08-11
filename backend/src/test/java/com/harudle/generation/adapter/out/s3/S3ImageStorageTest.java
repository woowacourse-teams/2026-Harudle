package com.harudle.generation.adapter.out.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.generation.configuration.S3StorageProperties;
import com.harudle.generation.service.port.GeneratedImage;
import com.harudle.generation.service.port.ImageStorageException;
import com.harudle.generation.service.port.ReferenceImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

@ExtendWith(MockitoExtension.class)
class S3ImageStorageTest {

    private static final UUID GENERATION_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final String OBJECT_KEY =
            "generated/diary-images/550e8400-e29b-41d4-a716-446655440000/image.png";
    private static final int MAX_OBJECT_SIZE_BYTES = 10;

    @Mock
    private S3Client s3Client;

    private S3ImageStorage imageStorage;

    @BeforeEach
    void setUp() {
        S3StorageProperties properties = new S3StorageProperties(
                "test-bucket",
                "ap-northeast-2",
                "generated/diary-images",
                DataSize.ofBytes(MAX_OBJECT_SIZE_BYTES)
        );
        imageStorage = new S3ImageStorage(
                s3Client,
                properties,
                new ImageObjectKeyFactory(properties),
                new S3ExceptionTranslator()
        );
    }

    @Test
    @DisplayName("생성 이미지를 정해진 Object Key와 Content-Type으로 저장한다")
    void storeGeneratedImage() throws IOException {
        byte[] imageBytes = "generated".getBytes(StandardCharsets.UTF_8);
        GeneratedImage generatedImage = new GeneratedImage(
                new ByteArrayResource(imageBytes),
                MediaType.IMAGE_PNG
        );
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        String storedObjectKey = imageStorage.store(GENERATION_ID, generatedImage);

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(requestCaptor.capture(), bodyCaptor.capture());

        PutObjectRequest request = requestCaptor.getValue();
        assertThat(storedObjectKey).isEqualTo(OBJECT_KEY);
        assertThat(request.bucket()).isEqualTo("test-bucket");
        assertThat(request.key()).isEqualTo(OBJECT_KEY);
        assertThat(request.contentType()).isEqualTo("image/png");
        assertThat(request.contentLength()).isEqualTo(imageBytes.length);
        assertThat(bodyCaptor.getValue().optionalContentLength()).contains((long) imageBytes.length);
        assertThat(bodyCaptor.getValue().contentStreamProvider().newStream().readAllBytes())
                .isEqualTo(imageBytes);
    }

    @Test
    @DisplayName("S3 이미지 객체를 메모리 Resource로 읽고 응답 스트림을 닫는다")
    void loadReferenceImage() throws IOException {
        byte[] imageBytes = "reference".getBytes(StandardCharsets.UTF_8);
        CloseTrackingInputStream inputStream = new CloseTrackingInputStream(imageBytes);
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenReturn(responseStream(inputStream, imageBytes.length, "image/png"));

        ReferenceImage referenceImage = imageStorage.load("prompt-assets/reference.png");

        ArgumentCaptor<GetObjectRequest> requestCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(requestCaptor.capture());
        assertThat(requestCaptor.getValue().bucket()).isEqualTo("test-bucket");
        assertThat(requestCaptor.getValue().key()).isEqualTo("prompt-assets/reference.png");
        assertThat(referenceImage.mediaType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(referenceImage.resource().getContentAsByteArray()).isEqualTo(imageBytes);
        assertThat(inputStream.isClosed()).isTrue();
    }

    @Test
    @DisplayName("설정된 최대 크기를 넘는 생성 이미지는 S3에 저장하지 않는다")
    void rejectOversizedGeneratedImage() {
        byte[] oversizedImage = new byte[MAX_OBJECT_SIZE_BYTES + 1];
        GeneratedImage generatedImage = new GeneratedImage(
                new ByteArrayResource(oversizedImage),
                MediaType.IMAGE_PNG
        );

        assertThatThrownBy(() -> imageStorage.store(GENERATION_ID, generatedImage))
                .isInstanceOf(ImageStorageException.class)
                .hasMessageContaining("S3 이미지 저장")
                .hasRootCauseMessage("S3 이미지 객체 크기가 허용 범위를 벗어났습니다.");
        verifyNoInteractions(s3Client);
    }

    @Test
    @DisplayName("S3 응답의 실제 이미지 크기가 제한을 넘으면 조회에 실패한다")
    void rejectOversizedResponseBody() {
        byte[] oversizedImage = new byte[MAX_OBJECT_SIZE_BYTES + 1];
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenReturn(responseStream(
                        new CloseTrackingInputStream(oversizedImage),
                        MAX_OBJECT_SIZE_BYTES,
                        "image/png"
                ));

        assertThatThrownBy(() -> imageStorage.load("prompt-assets/reference.png"))
                .isInstanceOf(ImageStorageException.class)
                .hasRootCauseMessage("S3 이미지 객체 크기가 허용 범위를 벗어났습니다.");
    }

    @Test
    @DisplayName("S3 객체의 Content-Type이 이미지가 아니면 조회에 실패한다")
    void rejectNonImageContentType() {
        byte[] objectBytes = "not-image".getBytes(StandardCharsets.UTF_8);
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenReturn(responseStream(
                        new CloseTrackingInputStream(objectBytes),
                        objectBytes.length,
                        "text/plain"
                ));

        assertThatThrownBy(() -> imageStorage.load("prompt-assets/reference.txt"))
                .isInstanceOf(ImageStorageException.class)
                .hasMessageContaining("S3 이미지 조회")
                .hasRootCauseMessage("S3 객체의 Content-Type은 구체적인 image/* 타입이어야 합니다.");
    }

    @Test
    @DisplayName("AWS SDK 오류를 이미지 저장소 예외로 변환한다")
    void translateSdkException() {
        SdkClientException cause = SdkClientException.builder()
                .message("connection failure")
                .build();
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(cause);

        assertThatThrownBy(() -> imageStorage.load("prompt-assets/reference.png"))
                .isInstanceOf(ImageStorageException.class)
                .hasMessageContaining("S3 이미지 조회")
                .hasMessageContaining("prompt-assets/reference.png")
                .hasCause(cause);
    }

    private static ResponseInputStream<GetObjectResponse> responseStream(
            CloseTrackingInputStream inputStream,
            long contentLength,
            String contentType
    ) {
        GetObjectResponse response = GetObjectResponse.builder()
                .contentLength(contentLength)
                .contentType(contentType)
                .build();
        return new ResponseInputStream<>(response, inputStream);
    }

    private static final class CloseTrackingInputStream extends ByteArrayInputStream {

        private boolean closed;

        private CloseTrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        private boolean isClosed() {
            return closed;
        }
    }
}
