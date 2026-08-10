package com.harudle.generation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.generation.service.port.ReferenceImage;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;

class ReferenceImageTest {

    @Test
    @DisplayName("읽을 수 있는 이미지 Resource로 참조 이미지를 생성한다")
    void createReferenceImage() {
        ByteArrayResource resource = new ByteArrayResource("reference".getBytes(StandardCharsets.UTF_8));

        ReferenceImage referenceImage = new ReferenceImage(resource, MediaType.IMAGE_PNG);

        assertThat(referenceImage.resource()).isSameAs(resource);
        assertThat(referenceImage.mediaType()).isEqualTo(MediaType.IMAGE_PNG);
    }

    @Test
    @DisplayName("Resource를 읽을 수 없으면 참조 이미지를 생성할 수 없다")
    void rejectUnreadableResource() {
        assertThatThrownBy(() -> new ReferenceImage(
                new ClassPathResource("missing-reference-image.png"),
                MediaType.IMAGE_PNG
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("읽을 수 있는");
    }

    @Test
    @DisplayName("이미지가 아닌 MediaType이면 참조 이미지를 생성할 수 없다")
    void rejectNonImageMediaType() {
        assertThatThrownBy(() -> new ReferenceImage(
                new ByteArrayResource("reference".getBytes(StandardCharsets.UTF_8)),
                MediaType.TEXT_PLAIN
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MediaType");
    }
}
