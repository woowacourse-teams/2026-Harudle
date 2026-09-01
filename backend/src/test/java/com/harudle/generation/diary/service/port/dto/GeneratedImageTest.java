package com.harudle.generation.diary.service.port.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;

class GeneratedImageTest {

    @Test
    @DisplayName("읽을 수 있는 이미지 Resource로 생성 이미지를 만든다")
    void createGeneratedImage() {
        ByteArrayResource resource = new ByteArrayResource("generated".getBytes(StandardCharsets.UTF_8));

        GeneratedImage generatedImage = new GeneratedImage(resource, MediaType.IMAGE_PNG);

        assertThat(generatedImage.resource()).isSameAs(resource);
        assertThat(generatedImage.mediaType()).isEqualTo(MediaType.IMAGE_PNG);
    }

    @Test
    @DisplayName("이미지가 아닌 MediaType이면 생성 이미지를 만들 수 없다")
    void rejectNonImageMediaType() {
        assertThatThrownBy(() -> new GeneratedImage(
                new ByteArrayResource("generated".getBytes(StandardCharsets.UTF_8)),
                MediaType.APPLICATION_JSON
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MediaType");
    }
}
