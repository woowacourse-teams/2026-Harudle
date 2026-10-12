package com.harudle.generation.adapter.out.storyboard;

import com.harudle.generation.diary.service.port.dto.StoryboardGenerationRequest;

public final class StoryboardRequestRenderer {

    private static final String TEMPLATE = """
            <context>
            <diary>
            %s
            </diary>
            </context>

            <task>
            Based only on the diary above, create exactly one schema-compliant four-panel storyboard.
            Before returning, verify:
            - exactly four panels use setup → action → escalation → resolution in order
            - one source-faithful central cause-and-effect chain preserves the actual outcome
            - panel 3 contains the main turn and panel 4 completes or changes earlier meaning
            - the four captions form one connected verbal routine rather than four event labels
            - every valid character annotation is preserved consistently
            Return only the JSON.
            </task>""";

    private StoryboardRequestRenderer() {
    }

    public static String render(StoryboardGenerationRequest request) {
        return TEMPLATE.formatted(request.diaryText());
    }
}
