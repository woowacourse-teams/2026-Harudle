package com.harudle.generation.adapter.out.storyboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.generation.adapter.out.gemini.DiaryImagePromptRenderer;
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
    @DisplayName("색상 대상의 패널 번호는 모델 응답 대신 소품이 실제 등장하는 모든 패널에서 계산한다")
    void deriveColorPanelNumbersFromProps() {
        var storyboard = codec.decode(StoryboardTestFixtures.storyboardJsonWithMismatchedColorPanels());

        assertThat(storyboard.scenePlan().focalColors()).hasSize(2);
        assertThat(storyboard.scenePlan().focalColors().getFirst()).satisfies(color -> {
            assertThat(color.prop()).isEqualTo("laptop");
            assertThat(color.component()).isEqualTo("lid");
            assertThat(color.colorHex()).isEqualTo("#A99BE8");
            assertThat(color.panelNumbers()).containsExactly(1, 2, 4);
        });
        assertThat(storyboard.scenePlan().focalColors().getLast().panelNumbers()).containsExactly(3);
        assertThat(new DiaryImagePromptRenderer().render(storyboard))
                .contains("Panels 1, 2, 4: fill the entire visible lid of the existing laptop",
                        "Panel 3: fill the entire visible case of the existing phone");
    }

    @Test
    @DisplayName("소품 이름의 앞뒤 공백과 대소문자를 정규화하고 같은 패널의 중복 소품은 한 번만 센다")
    void deriveColorPanelsUsingNormalizedPropNames() {
        String json = StoryboardTestFixtures.storyboardJsonWithMismatchedColorPanels()
                .replace("\"prop\":\"laptop\"", "\"prop\":\" LAPTOP \"")
                .replace("\"props\":[\"laptop\"]", "\"props\":[\" laptop \",\"LAPTOP\"]");

        var storyboard = codec.decode(json);

        assertThat(storyboard.scenePlan().focalColors().getFirst()).satisfies(color -> {
            assertThat(color.prop()).isEqualTo("LAPTOP");
            assertThat(color.panelNumbers()).containsExactly(1, 2, 4);
        });
    }

    @Test
    @DisplayName("어느 패널에도 없는 색상 대상 소품은 패널 번호를 추측하지 않고 거부한다")
    void rejectColorPropMissingFromEveryPanel() {
        String json = StoryboardTestFixtures.storyboardJsonWithMismatchedColorPanels()
                .replace("\"prop\":\"laptop\"", "\"prop\":\"tablet\"");

        assertThatThrownBy(() -> codec.decode(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("props에 존재해야 합니다");
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
