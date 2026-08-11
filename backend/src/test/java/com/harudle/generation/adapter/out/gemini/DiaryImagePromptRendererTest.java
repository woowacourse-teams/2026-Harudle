package com.harudle.generation.adapter.out.gemini;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.generation.domain.StoryPanel;
import com.harudle.generation.domain.Storyboard;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class DiaryImagePromptRendererTest {

    private final DiaryImagePromptRenderer renderer = new DiaryImagePromptRenderer();

    @Test
    @DisplayName("스토리보드를 Gemini 이미지용 이야기 프롬프트로 변환한다")
    void renderDiaryImagePrompt() throws IOException {
        Storyboard storyboard = createStoryboard();

        String renderedPrompt = renderer.render(storyboard);

        assertThat(renderedPrompt).isEqualTo(readGoldenPrompt());
    }

    private static Storyboard createStoryboard() {
        return new Storyboard(
                "인스타 맛집의 함정",
                "A young protagonist with short dark hair in casual clothes, "
                        + "appearing consistently across all panels.",
                List.of(
                        new StoryPanel(
                                1,
                                "와, 침 고인다",
                                "A person sitting on a sofa, eyes wide and sparkling, staring intently at a "
                                        + "smartphone screen showing a glowing, vibrant food photo.",
                                "Protagonist sitting comfortably on a couch, holding a smartphone with both hands.",
                                "Excitement, wide-eyed fascination, tiny open mouth.",
                                List.of("smartphone")
                        ),
                        new StoryPanel(
                                2,
                                "막상 먹어보면...",
                                "The same person sitting at a restaurant table, looking blankly at a very "
                                        + "ordinary, underwhelming dish with chopsticks in hand.",
                                "Protagonist sitting at a simple cafe table, holding chopsticks awkwardly "
                                        + "above the plate.",
                                "Disappointment, flat expression, tiny straight line mouth.",
                                List.of("plate", "chopsticks", "table")
                        ),
                        new StoryPanel(
                                3,
                                "다신 안 속아",
                                "The person sitting back with crossed arms, looking skeptical and determined "
                                        + "while staring off to the side.",
                                "Protagonist sitting with arms crossed, leaning back with a resolute expression.",
                                "Stubborn determination, tiny curved-down mouth.",
                                List.of()
                        ),
                        new StoryPanel(
                                4,
                                "이번엔 다를지도?",
                                "The person lying in bed at night, scrolling the smartphone again with a "
                                        + "hopeful grin, illuminated by the screen glow.",
                                "Protagonist lying under a blanket, holding the smartphone close with a soft smile.",
                                "Renewed anticipation, tiny hopeful smile.",
                                List.of("smartphone", "blanket")
                        )
                )
        );
    }

    private static String readGoldenPrompt() throws IOException {
        ClassPathResource resource = new ClassPathResource("gemini/example-story-prompt.txt");
        try (InputStream inputStream = resource.getInputStream()) {
            return new String(inputStream.readAllBytes(), UTF_8)
                    .replace("\r\n", "\n")
                    .stripTrailing();
        }
    }
}
