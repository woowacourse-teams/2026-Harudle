package com.harudle.generation.adapter.out.gemini;

import com.harudle.generation.diary.domain.StoryPanel;
import com.harudle.generation.diary.domain.Storyboard;
import java.util.ArrayList;
import java.util.List;

public final class DiaryImagePromptRenderer {

    private static final String TITLE_PREFIX = "# ";
    private static final String CREATOR_HANDLE = "@harudle.official";
    private static final String PANEL_HEADER_FORMAT = "Panel %d — %s — %s:";
    private static final String CAPTION_POSITION_RULE = "Place this caption inside the upper-left area "
            + "of this panel with consistent inner padding. Use clean scene negative space behind it; "
            + "never place it below the scene, outside the panel, in a separate caption band or strip, "
            + "or across a panel divider.";
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
        addFinalStoryCheck(lines, storyboard);
        return String.join("\n", lines);
    }

    private static void addStoryOverview(List<String> lines, Storyboard storyboard) {
        lines.add("SELECTED STORY: " + storyboard.title());
        lines.add("This selected-story line is internal metadata only. Never render it inside any panel.");
        lines.add("");
        lines.add("SOURCE AND ADAPTATION RULE:");
        lines.add("This four-panel story was adapted from one diary entry. Preserve this intentional "
                + "reveal order, cast, causal logic, meaning, and ending. Do not add another event, "
                + "character, story-world brand, or subplot. The fixed creator handle \""
                + CREATOR_HANDLE + "\" is required metadata outside the panel grid.");
        lines.add("");
        lines.add("CAST AND CONTINUITY:");
        lines.add(storyboard.castContinuity());
        lines.add("Use the same recognizable character designs in every panel where they recur.");
        lines.add("Preserve source-required clothing marks, accessories, logos, or brands explicitly "
                + "defined in CAST AND CONTINUITY. Render each only on its assigned character, never as "
                + "standalone background text, signage, or decoration.");
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
        lines.add("Do not add unrequested readable text, labels, logos, brands, model names, or UI words "
                + "inside the scene. Preserve only source-required character marks, accessories, logos, "
                + "or brands explicitly assigned in CAST AND CONTINUITY.");
        lines.add("Caption reads exactly: \"%s\"".formatted(panel.caption()));
        lines.add(CAPTION_POSITION_RULE);
        lines.add("This panel contains exactly one readable text block: its assigned caption.");
        lines.add("Never place the comic title, footer title, creator handle, or any text beginning with \"#\" "
                + "inside this panel.");
    }

    private static String renderProps(List<String> props) {
        if (props.isEmpty()) {
            return "No additional prop";
        }
        return String.join(", ", props);
    }

    private static void addFinalStoryCheck(List<String> lines, Storyboard storyboard) {
        String visibleTitle = TITLE_PREFIX + storyboard.title();
        lines.add("");
        lines.add("FINAL STORY CHECK:");
        lines.add("Exactly four equal 2x2 panels in top-left to bottom-right reading order. Follow "
                + "the storyboard's clear chronological cause-and-effect order.");
        lines.add("Render exactly six readable text blocks: one comic title, four panel captions, "
                + "and one creator handle.");
        lines.add("The four panel captions read exactly:");
        for (StoryPanel panel : storyboard.panels()) {
            lines.add("- \"" + panel.caption() + "\"");
        }
        lines.add("All four captions must appear at matching upper-left positions inside their assigned "
                + "panels. Never use bottom caption bands, external caption strips, or captions crossing "
                + "panel dividers.");
        lines.add("Do not render any other readable text, date, additional hashtag, unrequested logo, "
                + "signature, or footer label. Small source-required character marks or logos defined in "
                + "CAST AND CONTINUITY are visual identity details, not additional text blocks.");
        lines.add("Keep the ending faithful to the diary. Square 1:1 canvas.");
        lines.add("");
        lines.add("FINAL FOOTER LOCK — HIGHEST LAYOUT PRIORITY:");
        lines.add("Create one separate white footer band below the complete four-panel grid.");
        lines.add("The footer is outside every panel and outside the grid border.");
        lines.add("Render exactly these two footer text blocks on the same footer line:");
        lines.add("- left: \"" + visibleTitle + "\"");
        lines.add("- right: \"" + CREATOR_HANDLE + "\"");
        lines.add("Left-align the footer title with the grid's left edge and right-align the creator handle "
                + "with the grid's right edge.");
        lines.add("Never place either footer text inside Panel 1, Panel 2, Panel 3, or Panel 4.");
        lines.add("No footer character, including \"#\", \"@\", or \".\", may cross or touch the grid border.");
        lines.add("Do not render the footer title or creator handle above the grid.");
    }
}
