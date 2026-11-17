package com.harudle.generation.adapter.out.openai;

import com.harudle.generation.adapter.out.storyboard.StoryboardJsonCodec;
import com.harudle.generation.adapter.out.storyboard.StoryboardRequestRenderer;
import com.harudle.generation.adapter.out.storyboard.StoryboardResponseSchema;
import com.harudle.generation.diary.service.port.dto.StoryboardGenerationRequest;
import java.util.Objects;

public final class OpenAiStoryboardRequestFactory {

    /** JSON 객체 응답 모드는 메시지에 JSON 출력 지시가 있어야 하므로 프롬프트 내용과 관계없이 항상 붙인다. */
    private static final String JSON_ONLY_GUIDANCE = "Return only one JSON object that follows the output schema.";

    private final OpenAiStoryboardSettings settings;
    private final StoryboardJsonCodec codec;

    public OpenAiStoryboardRequestFactory(OpenAiStoryboardSettings settings, StoryboardJsonCodec codec) {
        this.settings = Objects.requireNonNull(settings);
        this.codec = Objects.requireNonNull(codec);
    }

    public OpenAiChatClient.Request create(StoryboardGenerationRequest request) {
        return new OpenAiChatClient.Request(settings.model(), systemPrompt(request),
                StoryboardRequestRenderer.render(request), settings.maxOutputTokens(), settings.reasoningEffort(),
                settings.strictSchema(), StoryboardResponseSchema.schema());
    }

    public String systemPrompt(StoryboardGenerationRequest request) {
        String prompt = request.storyboardPromptText();
        if (!settings.strictSchema()) {
            prompt += "\n\n<output_schema>\n" + codec.schemaJson() + "\n</output_schema>";
        }
        return prompt + "\n\n" + JSON_ONLY_GUIDANCE;
    }
}
