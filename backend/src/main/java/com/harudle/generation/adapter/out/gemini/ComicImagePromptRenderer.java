package com.harudle.generation.adapter.out.gemini;

import com.harudle.generation.domain.StoryPanel;
import com.harudle.generation.domain.Storyboard;
import java.util.ArrayList;
import java.util.List;

public final class ComicImagePromptRenderer {

    private static final String PANEL_HEADER_FORMAT = "Panel %d — %s — %s:";
    private static final List<String> PANEL_POSITIONS = List.of(
            "TOP LEFT",
            "TOP RIGHT",
            "BOTTOM LEFT",
            "BOTTOM RIGHT"
    );
    private static final List<String> STORY_ROLES = List.of(
            "SETUP",
            "ACTION",
            "ESCALATION",
            "RESOLUTION"
    );

    public String render(Storyboard storyboard) {
        List<String> lines = new ArrayList<>();
        addStoryOverview(lines, storyboard);
        addPanels(lines, storyboard.panels());
        addFinalStoryCheck(lines, storyboard.panels());
        return String.join("\n", lines);
    }

    private static void addStoryOverview(List<String> lines, Storyboard storyboard) {
        lines.add("SELECTED STORY: " + storyboard.title());
        lines.add("");
        lines.add("SOURCE AND ADAPTATION RULE:");
        lines.add("This four-panel story was adapted from one diary entry. Preserve this intentional "
                + "reveal order, cast, causal logic, meaning, and ending. Do not add another event, "
                + "character, brand, or subplot.");
        lines.add("");
        lines.add("CAST AND CONTINUITY:");
        lines.add(storyboard.castContinuity());
        lines.add("Use the same recognizable character designs in every panel where they recur.");
        lines.add("");
    }

    private static void addPanels(List<String> lines, List<StoryPanel> panels) {
        for (int index = 0; index < panels.size(); index++) {
            addPanel(lines, panels.get(index), index);
            if (index < panels.size() - 1) {
                lines.add("");
            }
        }
    }

    private static void addPanel(List<String> lines, StoryPanel panel, int index) {
        lines.add(PANEL_HEADER_FORMAT.formatted(
                panel.panelNumber(),
                PANEL_POSITIONS.get(index),
                STORY_ROLES.get(index)
        ));
        lines.add("Scene: " + panel.scene());
        lines.add("Characters and action: " + panel.characters());
        lines.add("Visible emotion: " + panel.emotion());
        lines.add("Sparse symbolic props: " + renderProps(panel.props()));
        lines.add("Do not place readable text, labels, logos, brands, model names, or UI words "
                + "inside the scene.");
        lines.add("Caption reads exactly: \"%s\"".formatted(panel.caption()));
    }

    private static String renderProps(List<String> props) {
        if (props.isEmpty()) {
            return "No additional prop";
        }
        return String.join(", ", props);
    }

    private static void addFinalStoryCheck(List<String> lines, List<StoryPanel> panels) {
        lines.add("");
        lines.add("FINAL STORY CHECK:");
        lines.add("Exactly four equal 2x2 panels in top-left to bottom-right reading order. Follow "
                + "the storyboard's clear chronological cause-and-effect order.");
        lines.add("Render exactly these four Korean captions once each and no other readable text:");
        for (StoryPanel panel : panels) {
            lines.add("- \"" + panel.caption() + "\"");
        }
        lines.add("Keep the ending faithful to the diary. Square 1:1 canvas.");
    }
}
