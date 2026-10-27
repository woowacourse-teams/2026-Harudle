package com.harudle.generation.adapter.out.qwen;

import com.harudle.generation.adapter.out.storyboard.StoryboardJsonCodec;
import com.harudle.generation.adapter.out.storyboard.StoryboardRequestRenderer;
import com.harudle.generation.adapter.out.storyboard.StoryboardResponseSchema;
import com.harudle.generation.diary.service.port.dto.StoryboardGenerationRequest;
import java.util.Objects;

public final class QwenStoryboardRequestFactory {

    private static final String CAPTION_GUIDANCE = """
            <qwen_caption_focus>
            캡션의 권장 길이는 기존 프롬프트·스키마의 안내 대신 공백 포함 8~20자로 한다. 최대 24자 제한은 지킨다.
            상황에 맞는 짧은 감탄사는 허용한다. 네 컷을 감탄사나 짧은 상태 설명으로만 채우지 않는다.
            인물의 입에서 나올 법한 대사나 속마음으로 쓰고, 말투와 감정이 드러나도록 조사·어미를 자연스럽게 살린다.
            그림으로 보이는 행동에 인물의 태도나 해석을 더한다. 네 캡션은 하나의 짧은 대화처럼 연결한다.
            마지막 캡션은 앞 컷의 단어·기대·행동을 받아 실제 결과에 맞게 의미를 바꾸거나 구체적인 재미를 완성한다.
            원문의 핵심 단어가 자연스럽게 받쳐주면 중의성·어감·표현의 비틀기를 활용한다. 그렇지 않으면 대조·콜백으로 살린다.
            같은 네 컷의 두 캡션 후보 중 사실에 충실하고, 소리 내 읽기 자연스러우며, 마지막 컷이 앞의 말을 잘 살리는 흐름을 고른다.
            농담을 위해 사건·결말·인물 정보를 만들지 않는다. 원문에 맞게 따뜻하거나 담백한 마무리도 허용한다.
            선택한 캡션을 포함한 스토리보드 전체를 기존 JSON 형식으로만 반환한다.
            </qwen_caption_focus>""";

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
        String prompt = request.storyboardPromptText();
        if (!settings.strictSchema()) {
            prompt += "\n\n<output_schema>\n" + codec.schemaJson() + "\n</output_schema>";
        }
        return prompt + "\n\n" + CAPTION_GUIDANCE;
    }
}
