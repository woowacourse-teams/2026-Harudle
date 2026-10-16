package com.harudle.generation.adapter.out.storyboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.generation.adapter.out.gemini.DiaryImagePromptRenderer;
import com.harudle.generation.adapter.out.gemini.GeminiStoryboardResponseSchema;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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

    @ParameterizedTest
    @ValueSource(strings = {"[1]", "[1,1]", "[4,1]"})
    @DisplayName("색상 패널 번호의 누락·중복·역순은 소품이 실제 등장하는 모든 패널 번호로 교정한다")
    void deriveColorPanelNumbersFromProps(String modelPanelNumbers) {
        String json = StoryboardTestFixtures.storyboardJsonWithMismatchedColorPanels()
                .replace("\"panel_numbers\":[1]", "\"panel_numbers\":" + modelPanelNumbers);

        var storyboard = codec.decode(json);

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

    @ParameterizedTest
    @CsvSource(value = {
            "[] | too few items",
            "null | expected array",
            " | required field is missing",
            "[5] | above maximum",
            "[\"1\"] | expected integer"
    }, delimiter = '|')
    @DisplayName("색상 패널 번호의 형식 오류는 번호 교정 전에 기존 스키마 검증에서 거부한다")
    void rejectInvalidColorPanelNumbers(String modelPanelNumbers, String reason) {
        String panelNumbersField = modelPanelNumbers == null ? "" : ",\"panel_numbers\":" + modelPanelNumbers;
        String json = StoryboardTestFixtures.storyboardJsonWithMismatchedColorPanels()
                .replace(",\"panel_numbers\":[1]", panelNumbersField);

        assertThatThrownBy(() -> codec.decode(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll("Storyboard schema violation",
                        "$.visual_plan.focal_colors[0].panel_numbers", reason);
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
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("어느 패널에도 없는 색상 대상만 제외하고 유효한 색상과 스토리보드는 유지한다")
    void ignoreColorPropMissingFromEveryPanel(CapturedOutput output) {
        String json = StoryboardTestFixtures.storyboardJsonWithMismatchedColorPanels()
                .replace("\"prop\":\"laptop\"", "\"prop\":\"private-missing-prop\"");

        var storyboard = codec.decode(json);

        assertThat(storyboard.panels()).isEqualTo(codec.decode(StoryboardTestFixtures.storyboardJson()).panels());
        assertThat(storyboard.scenePlan().focalColors()).singleElement().satisfies(color -> {
            assertThat(color.prop()).isEqualTo("phone");
            assertThat(color.colorHex()).isEqualTo("#55AAFF");
            assertThat(color.panelNumbers()).containsExactly(3);
        });
        assertThat(new DiaryImagePromptRenderer().render(storyboard))
                .contains("Panel 3: fill the entire visible case of the existing phone")
                .doesNotContain("private-missing-prop");
        assertThat(output).contains("event=storyboard_color_targets_ignored", "ignoredCount=1")
                .doesNotContain("private-missing-prop", "발표 직전", "#55AAFF", "#A99BE8");
    }

    @Test
    @DisplayName("모든 컷의 소품이 비어 있으면 색상 계획만 비우고 이미지 생성용 스토리보드를 반환한다")
    void continueWithoutColorsWhenEveryPanelHasNoProps() {
        String json = StoryboardTestFixtures.storyboardJsonWithMismatchedColorPanels()
                .replace("\"props\":[\"laptop\"]", "\"props\":[]")
                .replace("\"props\":[\"phone\"]", "\"props\":[]");

        var storyboard = codec.decode(json);

        assertThat(storyboard.title()).isEqualTo("발표 직전");
        assertThat(storyboard.panels()).hasSize(4)
                .allSatisfy(panel -> assertThat(panel.props()).isEmpty());
        assertThat(storyboard.scenePlan().environmentRule()).isEqualTo("All panels remain in the same office.");
        assertThat(storyboard.scenePlan().focalColors()).isEmpty();
        assertThat(new DiaryImagePromptRenderer().render(storyboard))
                .contains("Do not introduce a colored prop; preserve the mostly black-and-white scene.");
    }

    @Test
    @DisplayName("색상 대상 소품 이름이 비어 있으면 기존 필수 값 검증으로 거부한다")
    void rejectBlankColorProp() {
        String json = StoryboardTestFixtures.storyboardJsonWithMismatchedColorPanels()
                .replace("\"prop\":\"laptop\"", "\"prop\":\" \"");

        assertThatThrownBy(() -> codec.decode(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("색상 대상 소품은 필수입니다");
    }

    @ParameterizedTest
    @CsvSource({"not-a-color", "#12345"})
    @DisplayName("실제 등장하는 소품의 잘못된 색상 코드는 기존 도메인 검증으로 거부한다")
    void rejectInvalidColorForExistingProp(String colorHex) {
        String json = StoryboardTestFixtures.storyboardJsonWithMismatchedColorPanels()
                .replace("#A99BE8", colorHex);

        assertThatThrownBy(() -> codec.decode(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("색상 코드는 #RRGGBB 형식이어야 합니다");
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
