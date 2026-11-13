// 스토리 본문은 백엔드 DiaryImagePromptRenderer의 항목을 따르되, 데모데이 엽서 프레임용으로 줄인다.
// 실제 운영에서 AI가 프레임을 무시하고 칸을 새로 그리는 실패가 잦아(칸 위치 180~315px 차이),
// 프레임 고정 지시를 맨 앞과 맨 끝에 두고 칸마다 반복하던 공통 규칙은 한 번만 쓴다.

const CREATOR_HANDLE = "@harudle.official";
const PANEL_POSITIONS = ["TOP LEFT", "TOP RIGHT", "BOTTOM LEFT", "BOTTOM RIGHT"];
const STORY_ROLES = ["SETUP", "ACTION", "ESCALATION", "RESOLUTION"];

// 프레임(1024x1536 기준) 칸 외곽선 위치를 비율로 적는다. 프레임을 바꾸면 composite.mjs LAYOUT과 함께 확인한다.
const FRAME_GEOMETRY = "The four white rounded panels form a 2x2 grid that almost fills the card: "
  + "left panels span about 3%-50% of the width and right panels about 50%-97%; "
  + "top panels span about 7%-48% of the height and bottom panels about 49%-89%. "
  + "The mint strip above the panels holds the frame's top text, and the mint strip below them holds the bird, "
  + `the comic title line, "${CREATOR_HANDLE}", the puppy, and the QR code.`;

// 2K 모델은 참조 캐릭터 구성을 느슨하게 따라 주인공 머리를 빼먹거나 검게 채우는 경우가 있어 명시한다
const PROTAGONIST_RULE = "The protagonist (the diary writer, \"I\") is the reference's canonical character: a plain "
  + "round white head with dot eyes and a simple mouth, topped by a row of 4 to 6 curly loops drawn only as thin "
  + "black outlines that stay white inside, with the round head outline visible underneath. Never fill the hair "
  + "black, never draw a hair cap, bowl cut, or helmet, and never draw the protagonist bald. Keep this hair in every "
  + "panel, even when the head is turned or partly covered. Curly loops belong to the protagonist only: any other "
  + "person gets a different outlined non-curly style (short straight hair with a fringe, a short spiky crop, or a "
  + "neat bob) kept the same in every panel.";

// 프레임 글씨("우아한테크코스 8기 데모데이", "@harudle.official")와 같은 모양으로 쓰게 한다
const FRAME_LETTERING = "the same lettering style as the frame's printed text \"우아한테크코스 8기 데모데이\" and "
  + `"${CREATOR_HANDLE}": rounded, evenly weighted, medium-weight Korean handwriting with soft round stroke ends, `
  + "slightly irregular but neat, not bold, not blocky, not condensed";

export const REFERENCE_INSTRUCTION = "[Image 1: Style Reference]\n"
  + "The following image is the single binding style and protagonist reference. Apply only its drawing style and "
  + "protagonist construction. Do not copy its story, panel count, layout, composition, props, poses, or text.";

export const FRAME_INSTRUCTION = "[Image 2: Fixed Postcard Frame = Your Canvas]\n"
  + "The following image is the exact canvas to draw on. Your output must be this same image, pixel-aligned, with "
  + "only the white interiors of its four existing panels and the comic title line filled in. Do not draw a new grid.";

const FRAME_LOCK = `FIXED POSTCARD FRAME LOCK — READ FIRST, HIGHEST PRIORITY:
Your output is Image 2 itself with the comic drawn inside it. Same 2:3 size, same positions, same everything outside the panel interiors.
${FRAME_GEOMETRY}
Use the frame's own four panels exactly where they are. Never draw your own panel grid, borders, gutters, or margins; never move, resize, shrink, re-space, or re-round the panels; never zoom, crop, pad, or shift the card.
Keep every frame element unchanged: the mint background, the thick black panel outlines, the top text, the planet and sparkles, the spiky green creature, the white bird with a cane, the white puppy, the QR code, and "${CREATOR_HANDLE}".
Draw scenes only on the white interior of each panel, keeping every character, object, and caption clear of the black outline.
Keep these overlapped panel corners empty: upper-right of the top-right panel under the planet's legs, lower-left of the top-left panel, lower-left of the bottom-left panel, lower-right of the bottom-right panel.`;

function story(storyboard) {
  const lines = [
    "SELECTED STORY: " + storyboard.title,
    "This is internal metadata; never render this line.",
    "Adapted from one diary entry. Keep its reveal order, cast, causal logic, meaning, and ending. Do not add events, "
      + "characters, brands, or subplots.",
    "",
    "CAST AND CONTINUITY:",
    storyboard.castContinuity,
    "Keep each character's design the same in every panel. Render source-required clothing marks, accessories, "
      + "logos, or brands only on their assigned character.",
    PROTAGONIST_RULE,
    "",
    "ENVIRONMENT:",
    storyboard.scenePlan.environmentRule,
    "",
    "FOCAL COLORS (not visible text):",
  ];
  const { focalColors } = storyboard.scenePlan;
  if (focalColors.length === 0) lines.push("No colored prop; keep the scene mostly black and white.");
  for (const c of focalColors) {
    const label = c.panelNumbers.length === 1 ? "Panel " : "Panels ";
    lines.push(`- ${label}${c.panelNumbers.join(", ")}: fill the entire visible ${c.component} of the ${c.prop} `
      + `with uniform opaque ${c.colorHex}, the same in every appearance.`);
  }
  return lines;
}

function panel(p, index) {
  return [
    `Panel ${p.panelNumber} — frame's ${PANEL_POSITIONS[index]} panel — ${STORY_ROLES[index]}:`,
    "Scene: " + p.scene,
    "Characters and action: " + p.characters,
    "Emotion: " + p.emotion,
    "Props: " + (p.props.length ? p.props.join(", ") : "none"),
    `Caption reads exactly: "${p.caption}"`,
  ];
}

function textRules(storyboard) {
  return `TEXT RULES:
Render exactly five new readable text blocks: the four captions above and one comic title. Nothing else: no date, hashtag, logo, signature, sound effect, label, or extra text.
Each caption sits once inside the upper-left area of its own panel on clean scene background, on one line within the left 70% of the panel width (write it slightly smaller if needed). Never put a caption below the scene, in a separate band, outside the panel, or across a divider. The top-right panel's caption must end well before the planet corner.
Phones, screens, chats, documents, signs, clocks, and packaging stay text-free: blank surfaces or simple icons only.

LETTERING:
Write all four captions and the comic title in ${FRAME_LETTERING}. Use the same letter size, stroke weight, and color for all four captions.

COMIC TITLE:
Handwrite exactly "# ${storyboard.title}" once, in pure black, on the mint strip below the bottom-left panel: left-aligned with that panel's left edge, on its own line directly above "${CREATOR_HANDLE}", right of the bird's feet and left of the QR code. Make it about the same height and stroke weight as the top text "우아한테크코스 8기 데모데이", never bolder. Never place it inside a panel or above the panels.`;
}

const FINAL_CHECK = `FINAL CHECK BEFORE RENDERING:
1. Overlay test: laid over Image 2, your output's panel outlines, top text, creatures, QR code, and handle line up exactly. If you drew your own grid or moved any panel, redo it on Image 2's panels.
2. The protagonist has outlined white curly loop hair in every panel, and nobody else has curly loops.
3. Exactly four captions and one title, with no other new text.`;

export function renderFrameFinalTask(storyboard) {
  const panels = storyboard.panels.flatMap((p, i) => [...panel(p, i), ""]);
  return ["[Final Task]", FRAME_LOCK, "", ...story(storyboard), "", "PANELS (top-left, top-right, bottom-left, "
    + "bottom-right reading order):", ...panels, textRules(storyboard), "", FINAL_CHECK].join("\n");
}
