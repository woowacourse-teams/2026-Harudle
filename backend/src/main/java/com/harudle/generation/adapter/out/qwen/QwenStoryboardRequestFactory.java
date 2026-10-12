package com.harudle.generation.adapter.out.qwen;

import com.harudle.generation.adapter.out.storyboard.StoryboardJsonCodec;
import com.harudle.generation.adapter.out.storyboard.StoryboardRequestRenderer;
import com.harudle.generation.adapter.out.storyboard.StoryboardResponseSchema;
import com.harudle.generation.diary.service.port.dto.StoryboardGenerationRequest;
import java.util.Objects;

public final class QwenStoryboardRequestFactory {

    private final QwenStoryboardSettings settings;
    private final StoryboardJsonCodec codec;

    public QwenStoryboardRequestFactory(QwenStoryboardSettings settings, StoryboardJsonCodec codec) {
        this.settings = Objects.requireNonNull(settings);
        this.codec = Objects.requireNonNull(codec);
    }

    public QwenChatClient.Request create(StoryboardGenerationRequest request) {
        return new QwenChatClient.Request(settings.model(), systemPrompt(request),
                StoryboardRequestRenderer.render(request), settings.maxOutputTokens(), settings.thinking(),
                settings.thinkingBudget(), settings.strictSchema(), StoryboardResponseSchema.schema());
    }

    public String systemPrompt(StoryboardGenerationRequest request) {
        if (settings.strictSchema()) {
            return request.storyboardPromptText();
        }
        return request.storyboardPromptText() + "\n\n<output_schema>\n" + codec.schemaJson() + "\n</output_schema>";
    }
}
