// 백엔드 StoryboardRequestRenderer / StoryboardResponseSchema / StoryboardResponseMapper를 옮긴 것.
// 백엔드 쪽이 바뀌면 함께 맞춘다.

const STORY_ROLES = ["setup", "action", "escalation", "resolution"];

export function renderStoryboardRequest(diaryText) {
  return `<context>
<diary>
${diaryText}
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
</task>`;
}

const object = (properties, required = Object.keys(properties)) =>
  ({ type: "object", properties, required, propertyOrdering: required });

export const storyboardSchema = object({
  title: {
    type: "string",
    description: "A concise, natural Korean title, preferably 4-18 characters, "
      + "that hints at the central contrast without summarizing all events.",
  },
  cast_continuity: {
    type: "string",
    description: "In English, identify recurring people, source-supported names and relationships, group "
      + "membership or count, and only explicitly annotated persistent traits. Exclude plot events, temporary "
      + "actions or emotions, inferred appearance, and internal labels.",
  },
  panels: {
    type: "array",
    minItems: 4,
    maxItems: 4,
    items: object({
      panel_number: { type: "integer", minimum: 1, maximum: 4, description: "The one-based panel number in reading order." },
      story_role: { type: "string", enum: STORY_ROLES, description: "The panel's required narrative role." },
      caption: {
        type: "string",
        description: "One natural Korean caption rendered verbatim in the panel: one spoken beat and one primary "
          + "text block, preferably 2-18 Unicode characters including spaces and never more than 24.",
      },
      scene: {
        type: "string",
        description: "One concrete English sentence describing the setting, visible action, and composition. "
          + "Exclude hidden thoughts, caption repetition, persistent traits, and internal labels.",
      },
      characters: {
        type: "string",
        description: "In English, describe visible people, positions, actions, and interactions, including relevant "
          + "temporary annotated poses. Do not repeat the setting or persistent traits.",
      },
      emotion: { type: "string", description: "In English, describe only temporary visible facial and postural emotion." },
      props: {
        type: "array",
        maxItems: 3,
        items: { type: "string" },
        description: "Zero to three objects that must visibly appear in the panel; exclude implied or invisible objects.",
      },
    }),
  },
  visual_plan: object({
    environment_rule: {
      type: "string",
      description: "One or two English sentences preserving only the time, place, and movement already supported "
        + "by the four panels.",
    },
    focal_colors: {
      type: "array",
      description: "Color only meaningful existing props. Use an empty array when no prop has a justified color "
        + "target; never invent a prop to add color.",
      items: object({
        prop: { type: "string", description: "Exact name of an existing props entry in every listed panel." },
        component: { type: "string", description: "One small, clearly bounded visible surface of that prop to fill." },
        color_hex: { type: "string", description: "One fitting opaque solid color in #RRGGBB format." },
        panel_numbers: {
          type: "array",
          minItems: 1,
          maxItems: 4,
          items: { type: "integer", minimum: 1, maximum: 4 },
          description: "All panel numbers containing this prop, in ascending order. Use the same color for every appearance.",
        },
      }),
    },
  }),
});

const requiredText = (value, message) => {
  if (typeof value !== "string" || !value.trim()) throw new Error(message);
  return value.trim();
};

/** Gemini JSON 응답을 이미지 프롬프트용 스토리보드로 바꾼다. 백엔드 매퍼와 같은 규칙으로 검증한다. */
export function parseStoryboard(json) {
  const raw = typeof json === "string" ? JSON.parse(json) : json;
  if (!raw || !Array.isArray(raw.panels) || raw.panels.length !== 4) {
    throw new Error("스토리보드는 정확히 4개의 패널이 필요합니다.");
  }
  const panels = raw.panels.map((panel, index) => {
    if (panel?.story_role !== STORY_ROLES[index]) {
      throw new Error("스토리보드 역할은 setup, action, escalation, resolution 순서여야 합니다.");
    }
    return {
      panelNumber: panel.panel_number,
      caption: requiredText(panel.caption, "패널 caption은 필수입니다."),
      scene: requiredText(panel.scene, "패널 scene은 필수입니다."),
      characters: requiredText(panel.characters, "패널 characters는 필수입니다."),
      emotion: requiredText(panel.emotion, "패널 emotion은 필수입니다."),
      props: Array.isArray(panel.props) ? panel.props.filter(p => typeof p === "string" && p.trim()) : [],
    };
  });
  const plan = raw.visual_plan;
  if (!plan || !Array.isArray(plan.focal_colors)) throw new Error("스토리보드 시각 계획은 필수입니다.");
  const focalColors = plan.focal_colors.map(color => {
    const prop = requiredText(color?.prop, "색상 대상 소품은 필수입니다.");
    const panelNumbers = panels
      .filter(panel => panel.props.some(p => p.toLowerCase() === prop.toLowerCase()))
      .map(panel => panel.panelNumber);
    if (panelNumbers.length === 0) throw new Error("색상 대상 소품은 최소 한 패널의 props에 존재해야 합니다.");
    return { prop, component: color.component, colorHex: color.color_hex, panelNumbers };
  });
  return {
    title: requiredText(raw.title, "스토리보드 제목은 필수입니다."),
    castContinuity: requiredText(raw.cast_continuity, "cast_continuity는 필수입니다."),
    panels,
    scenePlan: { environmentRule: plan.environment_rule ?? "", focalColors },
  };
}
