package com.harudle.generation.adapter.out.storyboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.generation.adapter.out.gemini.GeminiStoryboardResponseSchema;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class StoryboardJsonCodecTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final StoryboardJsonCodec codec = new StoryboardJsonCodec(mapper, new StoryboardResponseMapper());

    @Test
    @DisplayName("공통 계약은 Provider 정보 없이 기존 이미지용 스토리보드를 만든다")
    void decodeProviderIndependentContract() {
        var storyboard = codec.decode(StoryboardTestFixtures.storyboardJson());
        assertThat(storyboard.panels()).hasSize(4);
        assertThat(storyboard.title()).isEqualTo("발표 직전");
        assertThat(codec.schemaJson()).doesNotContain("propertyOrdering");
        assertThat(GeminiStoryboardResponseSchema.schema()).containsKey("propertyOrdering");
    }

    @Test
    @DisplayName("추가 필드와 잘못된 타입을 거부해 Provider마다 다른 JSON 강제 변환을 방지한다")
    void rejectSchemaDriftAndTypeCoercion() {
        String json = StoryboardTestFixtures.storyboardJson();
        assertThatThrownBy(() -> codec.decode(json.replace("\"title\":", "\"extra\":true,\"title\":")))
                .hasMessageContaining("unknown field");
        assertThatThrownBy(() -> codec.decode(json.replace("\"panel_number\":1", "\"panel_number\":\"1\"")))
                .hasMessageContaining("expected integer");
        assertThatThrownBy(() -> codec.decode(json.replace("\"story_role\":\"action\"", "\"story_role\":\"unknown\"")))
                .hasMessageContaining("enum");
    }

    @Test
    @DisplayName("캡션 제한은 UTF-16 길이가 아니라 Unicode 문자 수로 동일하게 적용한다")
    void applyUnicodeCaptionLimit() {
        String json = StoryboardTestFixtures.storyboardJson();
        var storyboard = codec.decode(json.replace("\"caption\":\"준비 완료\"",
                "\"caption\":\"" + "🙂".repeat(24) + "\""));
        assertThat(storyboard.panels().getFirst().caption()).isEqualTo("🙂".repeat(24));
        assertThatThrownBy(() -> codec.decode(json.replace("\"caption\":\"준비 완료\"",
                "\"caption\":\"" + "🙂".repeat(25) + "\""))).hasMessageContaining("24");
    }

    @Test
    @DisplayName("한 Provider가 공유 스키마를 변경해 다른 Provider의 요청을 오염시킬 수 없다")
    @SuppressWarnings("unchecked")
    void keepSharedSchemaImmutable() {
        var schema = StoryboardResponseSchema.schema();
        assertThatThrownBy(() -> schema.put("type", "string")).isInstanceOf(UnsupportedOperationException.class);
        var properties = (Map<String, Object>) schema.get("properties");
        assertThatThrownBy(() -> properties.remove("title")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(mapper.valueToTree(schema).path("properties").path("panels").path("minItems").asInt())
                .isEqualTo(4);
    }
}
