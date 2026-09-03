package com.harudle.generation.diary.service.port.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.generation.diary.domain.StoryPanel;
import com.harudle.generation.diary.domain.Storyboard;
import com.harudle.generation.diary.service.port.dto.DiaryImageGenerationRequest;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;

class DiaryImageGenerationRequestTest {

    @Test
    @DisplayName("그림일기 이미지 생성 요청의 이미지 스타일 프롬프트 앞뒤 공백을 제거한다")
    void createDiaryImageGenerationRequest() {
        Storyboard storyboard = createStoryboard();
        ReferenceImage referenceImage = createReferenceImage();

        DiaryImageGenerationRequest request = new DiaryImageGenerationRequest(
                storyboard,
                " 검은 배경과 흰색 마커 스타일 ",
                referenceImage
        );

        assertThat(request.storyboard()).isSameAs(storyboard);
        assertThat(request.imageStylePromptText()).isEqualTo("검은 배경과 흰색 마커 스타일");
        assertThat(request.referenceImage()).isSameAs(referenceImage);
    }

    @Test
    @DisplayName("스토리보드가 없으면 그림일기 이미지 생성을 요청할 수 없다")
    void rejectNullStoryboard() {
        assertThatThrownBy(() -> new DiaryImageGenerationRequest(
                null,
                "이미지 스타일 프롬프트",
                createReferenceImage()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("스토리보드");
    }

    @Test
    @DisplayName("이미지 스타일 프롬프트가 비어 있으면 그림일기 이미지 생성을 요청할 수 없다")
    void rejectBlankImageStylePromptText() {
        assertThatThrownBy(() -> new DiaryImageGenerationRequest(
                createStoryboard(),
                " ",
                createReferenceImage()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이미지 스타일 프롬프트");
    }

    @Test
    @DisplayName("참조 이미지가 없으면 그림일기 이미지 생성을 요청할 수 없다")
    void rejectNullReferenceImage() {
        assertThatThrownBy(() -> new DiaryImageGenerationRequest(
                createStoryboard(),
                "이미지 스타일 프롬프트",
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("참조 이미지");
    }

    private Storyboard createStoryboard() {
        return new Storyboard(
                "친구와 보낸 하루",
                "같은 주인공이 모든 패널에 등장한다.",
                List.of(
                        createPanel(1, "첫 번째 캡션"),
                        createPanel(2, "두 번째 캡션"),
                        createPanel(3, "세 번째 캡션"),
                        createPanel(4, "네 번째 캡션")
                )
        );
    }

    private StoryPanel createPanel(int panelNumber, String caption) {
        return new StoryPanel(
                panelNumber,
                caption,
                "장면 " + panelNumber,
                "등장인물 " + panelNumber,
                "감정 " + panelNumber,
                List.of("소품 " + panelNumber)
        );
    }

    private ReferenceImage createReferenceImage() {
        return new ReferenceImage(
                new ByteArrayResource("reference".getBytes(StandardCharsets.UTF_8)),
                MediaType.IMAGE_PNG
        );
    }
}
