package com.harudle.generation.adapter.out.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.generation.configuration.S3StorageProperties;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;

class ImageObjectKeyFactoryTest {

    private static final UUID GENERATION_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @ParameterizedTest
    @CsvSource({
            "image/png, png",
            "image/jpeg, jpg",
            "image/webp, webp"
    })
    @DisplayName("이미지 MIME 타입에 맞는 확장자로 Object Key를 만든다")
    void createObjectKey(String mimeType, String extension) {
        ImageObjectKeyFactory factory = createFactory("generated/diary-images");

        String objectKey = factory.create(GENERATION_ID, MediaType.parseMediaType(mimeType));

        assertThat(objectKey).isEqualTo(
                "generated/diary-images/550e8400-e29b-41d4-a716-446655440000/image." + extension
        );
    }

    @Test
    @DisplayName("prefix의 구분자를 정규화한다")
    void normalizePrefix() {
        ImageObjectKeyFactory factory = createFactory("/generated\\diary-images/");

        String objectKey = factory.create(GENERATION_ID, MediaType.IMAGE_PNG);

        assertThat(objectKey).isEqualTo(
                "generated/diary-images/550e8400-e29b-41d4-a716-446655440000/image.png"
        );
    }

    @Test
    @DisplayName("지원하지 않는 이미지 MIME 타입이면 Object Key를 만들 수 없다")
    void rejectUnsupportedImageMediaType() {
        ImageObjectKeyFactory factory = createFactory("generated/diary-images");

        assertThatThrownBy(() -> factory.create(
                GENERATION_ID,
                MediaType.parseMediaType("image/gif")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("지원하지 않는 이미지 MediaType");
    }

    private static ImageObjectKeyFactory createFactory(String generatedPrefix) {
        S3StorageProperties properties = new S3StorageProperties(
                "test-bucket",
                "ap-northeast-2",
                generatedPrefix,
                DataSize.ofMegabytes(20)
        );
        return new ImageObjectKeyFactory(properties);
    }
}
