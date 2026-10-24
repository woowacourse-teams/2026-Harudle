package com.harudle.generation.adapter.out.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.generation.diary.domain.FocalColor;
import com.harudle.generation.diary.domain.ScenePlan;
import com.harudle.generation.diary.domain.StoryPanel;
import com.harudle.generation.diary.domain.Storyboard;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiaryImagePromptRendererTest {

    private final DiaryImagePromptRenderer renderer = new DiaryImagePromptRenderer();

    @Test
    void renderDiaryImagePrompt() {
        Storyboard storyboard = createStoryboard();

        String renderedPrompt = renderer.render(storyboard);

        assertThat(renderedPrompt)
                .startsWith("SELECTED STORY: 인스타 맛집의 함정")
                .contains("This selected-story line is internal metadata only. Never render it inside any panel.")
                .contains("Render exactly six readable text blocks: one comic title, four panel captions, "
                        + "and one creator handle.")
                .contains("FINAL FOOTER LOCK — HIGHEST LAYOUT PRIORITY:")
                .contains("- left: \"# 인스타 맛집의 함정\"")
                .contains("- right: \"@harudle.official\"")
                .contains("Never place either footer text inside Panel 1, Panel 2, Panel 3, or Panel 4.")
                .contains("- \"와, 침 고인다\"")
                .contains("- \"막상 먹어보면...\"")
                .contains("- \"다신 안 속아\"")
                .contains("- \"이번엔 다를지도?\"")
                .contains("All four captions must appear at matching upper-left positions inside their assigned "
                        + "panels. Never use bottom caption bands, external caption strips, or captions crossing "
                        + "panel dividers.")
                .contains("FINAL CANVAS AND GRID LOCK — HIGHEST LAYOUT PRIORITY:")
                .contains("Use a square 1:1 canvas with one flat pure-white background")
                .contains("exactly one vertical separator, and exactly one horizontal separator")
                .contains("All phones, screens, chats, documents, signs, clocks, and packaging must remain "
                        + "text-free: blank or non-text visuals only, with no letters, numbers, pseudo-text, "
                        + "fake Korean glyphs, or readable UI.")
                .doesNotContain("VISIBLE COMIC TITLE READS EXACTLY:")
                .doesNotContain("FIXED CREATOR HANDLE READS EXACTLY:")
                .doesNotContain("Render exactly these four Korean captions once each and no other readable text:")
                .doesNotContain("ENVIRONMENT FOR THIS STORY:", "FOCAL COLOR SUGGESTIONS");

        assertThat(renderedPrompt.lines()
                .filter("This panel contains exactly one readable text block: its assigned caption."::equals)
                .count())
                .isEqualTo(4);

        assertThat(renderedPrompt.lines()
                .filter(line -> line.startsWith("Place this caption inside the upper-left area"))
                .count())
                .isEqualTo(4);

        assertThat(renderedPrompt.lines()
                .filter(line -> line.startsWith("Inside scene objects—including phones, screens"))
                .count())
                .isEqualTo(4);
    }

    @Test
    void renderScenePlanBeforePanelsWithConsistentColor() {
        Storyboard baseStoryboard = createStoryboard();
        Storyboard storyboard = new Storyboard(
                baseStoryboard.title(),
                baseStoryboard.castContinuity(),
                baseStoryboard.panels(),
                new ScenePlan(
                        "Keep the same smartphone across panels 1 and 4.",
                        List.of(new FocalColor("smartphone", "phone case", "#A99BE8", List.of(1, 4)))
                )
        );

        String renderedPrompt = renderer.render(storyboard);

        assertThat(renderedPrompt)
                .contains("ENVIRONMENT FOR THIS STORY:\nKeep the same smartphone across panels 1 and 4.")
                .contains("FOCAL COLOR SUGGESTIONS — NOT VISIBLE TEXT:\n"
                        + "- Panels 1, 4: fill the entire visible phone case of the existing smartphone "
                        + "with uniform opaque #A99BE8; keep the same color in every appearance.");
        assertThat(renderedPrompt.indexOf("FOCAL COLOR SUGGESTIONS"))
                .isLessThan(renderedPrompt.indexOf("Panel 1 — TOP LEFT — SETUP:"));
    }

    @Test
    void renderScenePlanWithoutColorTarget() {
        Storyboard baseStoryboard = createStoryboard();
        Storyboard storyboard = new Storyboard(
                baseStoryboard.title(),
                baseStoryboard.castContinuity(),
                baseStoryboard.panels(),
                new ScenePlan("Keep the existing locations and time of day.", List.of())
        );

        String renderedPrompt = renderer.render(storyboard);

        assertThat(renderedPrompt)
                .contains("ENVIRONMENT FOR THIS STORY:\nKeep the existing locations and time of day.")
                .contains("FOCAL COLOR SUGGESTIONS — NOT VISIBLE TEXT:\n"
                        + "Do not introduce a colored prop; preserve the mostly black-and-white scene.")
                .doesNotContain("fill the entire visible");
    }

    @Test
    void preserveExplicitCharacterLogoFromCastContinuity() {
        Storyboard baseStoryboard = createStoryboard();
        String annotatedCast = "The protagonist wears a shirt with a T1 logo in every panel where shown.";
        Storyboard storyboard = new Storyboard(
                baseStoryboard.title(),
                annotatedCast,
                baseStoryboard.panels()
        );

        String renderedPrompt = renderer.render(storyboard);

        assertThat(renderedPrompt)
                .contains("CAST AND CONTINUITY:\n" + annotatedCast)
                .contains("Preserve source-required clothing marks, accessories, logos, or brands explicitly "
                        + "defined in CAST AND CONTINUITY.")
                .contains("Small source-required character marks or logos defined in "
                        + "CAST AND CONTINUITY are visual identity details, not additional text blocks.")
                .doesNotContain("Do not place readable text, labels, logos, brands, model names, or UI words");

        assertThat(renderedPrompt.lines()
                .filter(line -> line.startsWith("Do not add unrequested readable text"))
                .count())
                .isEqualTo(4);
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

}
