// 스토리 본문은 백엔드 DiaryImagePromptRenderer와 같은 문구로 만들고,
// 캔버스·그리드·푸터 규칙만 데모데이 엽서 프레임용으로 바꾼다.

const CREATOR_HANDLE = "@harudle.official";
const PANEL_POSITIONS = ["TOP LEFT", "TOP RIGHT", "BOTTOM LEFT", "BOTTOM RIGHT"];
const STORY_ROLES = ["SETUP", "ACTION", "ESCALATION", "RESOLUTION"];
const CAPTION_POSITION_RULE = "Place this caption inside the upper-left area of this panel with consistent inner "
  + "padding. Use clean scene negative space behind it; never place it below the scene, outside the panel, in a "
  + "separate caption band or strip, or across a panel divider. Keep the whole caption on one line within the left "
  + "70% of the panel width; if it does not fit, write it slightly smaller rather than extending it to the right edge.";
// 2K 모델은 참조 캐릭터 구성을 느슨하게 따라 주인공 머리를 빼먹는 경우가 있어 명시한다
const PROTAGONIST_RULE = "The protagonist (the diary writer, \"I\") is the attached reference's canonical character: "
  + "a plain round white head with dot eyes and a simple mouth, and the reference's signature hair, a row of 4 to 6 "
  + "loose curly loops along the top of the head. Draw each loop only as a thin black marker outline that stays white "
  + "inside, sitting on top of the round head outline, which stays fully visible underneath. Never fill the hair "
  + "with solid black, never draw a black hair cap, bowl cut, or helmet shape, and never place the loops inside a "
  + "black hair mass. Draw this exact curly hair in every panel where the protagonist appears, including when the "
  + "head is turned, tilted, covered by hands, or under an umbrella. Never draw the protagonist bald or with smooth, "
  + "spiky, filled, or different hair. "
  + "The curly loop hair belongs to the protagonist only. When other people appear (teammates, friends, strangers), "
  + "each must have clearly different non-curly hair and must never get curly loops: give each one a simple distinct "
  + "outlined style such as short straight hair with a flat fringe, a short spiky crop, or a neat bob, and keep each "
  + "person's style the same in every panel. If the protagonist is the only person in the story, ignore the rule for "
  + "other people and simply draw the protagonist's outlined curly loops. A viewer must be able to find the protagonist "
  + "instantly by the outlined curly loops alone.";
// 프레임 글씨("우아한테크코스 8기 데모데이", "@harudle.official")와 같은 모양으로 쓰게 한다
const FRAME_LETTERING = "the same lettering style as the frame's printed text \"우아한테크코스 8기 데모데이\" and "
  + `"${"@harudle.official"}": rounded, evenly weighted, medium-weight Korean handwriting with soft round stroke ends, `
  + "slightly irregular but neat, not bold, not blocky, not condensed";
const SCENE_OBJECT_TEXT_RULE = "Inside scene objects—including phones, screens, chats, documents, signs, clocks, "
  + "and packaging—render no letters, numbers, pseudo-text, fake Korean glyphs, or readable UI. Keep them text-free: "
  + "use blank surfaces or clear non-text visuals such as simple icons, pictograms, or images. The assigned panel "
  + "caption alone conveys any essential written or digital information.";

export const REFERENCE_INSTRUCTION = "[The Only Style Reference]\n"
  + "The following image is the single binding style and canonical protagonist reference described by the system "
  + "instruction. Apply only its visual style and protagonist construction. Do not copy its story, panel count, "
  + "composition, props, poses, or text, and do not assume another reference asset.";

export const FRAME_INSTRUCTION = "[Fixed Postcard Frame]\n"
  + "The following image is the fixed postcard frame to fill in. Preserve every frame element exactly and draw only "
  + "inside its four white panels plus the comic title line.";

function storyOverview(storyboard) {
  return [
    "SELECTED STORY: " + storyboard.title,
    "This selected-story line is internal metadata only. Never render it inside any panel.",
    "",
    "SOURCE AND ADAPTATION RULE:",
    "This four-panel story was adapted from one diary entry. Preserve this intentional reveal order, cast, causal "
      + "logic, meaning, and ending. Do not add another event, character, story-world brand, or subplot. The creator "
      + `handle "${CREATOR_HANDLE}" is already printed on the frame.`,
    "",
    "CAST AND CONTINUITY:",
    storyboard.castContinuity,
    "Use the same recognizable character designs in every panel where they recur.",
    PROTAGONIST_RULE,
    "Preserve source-required clothing marks, accessories, logos, or brands explicitly defined in CAST AND "
      + "CONTINUITY. Render each only on its assigned character, never as standalone background text, signage, or "
      + "decoration.",
    "",
  ];
}

function scenePlan({ environmentRule, focalColors }) {
  const lines = ["ENVIRONMENT FOR THIS STORY:", environmentRule, "", "FOCAL COLOR SUGGESTIONS — NOT VISIBLE TEXT:"];
  if (focalColors.length === 0) {
    lines.push("Do not introduce a colored prop; preserve the mostly black-and-white scene.");
  } else {
    for (const c of focalColors) {
      const label = c.panelNumbers.length === 1 ? "Panel " : "Panels ";
      lines.push(`- ${label}${c.panelNumbers.join(", ")}: fill the entire visible ${c.component} of the existing `
        + `${c.prop} with uniform opaque ${c.colorHex}; keep the same color in every appearance.`);
    }
  }
  lines.push("");
  return lines;
}

function panel(p, index) {
  return [
    `Panel ${p.panelNumber} — ${PANEL_POSITIONS[index]} — ${STORY_ROLES[index]}:`,
    "Scene: " + p.scene,
    "Characters and action: " + p.characters,
    "Visible emotion: " + p.emotion,
    "Sparse symbolic props: " + (p.props.length ? p.props.join(", ") : "No additional prop"),
    "Do not add unrequested readable text, labels, logos, brands, model names, or UI words inside the scene. "
      + "Preserve only source-required character marks, accessories, logos, or brands explicitly assigned in CAST AND "
      + "CONTINUITY.",
    SCENE_OBJECT_TEXT_RULE,
    `Caption reads exactly: "${p.caption}"`,
    CAPTION_POSITION_RULE,
    "This panel contains exactly one readable text block: its assigned caption.",
    "Never place the comic title, creator handle, or any text beginning with \"#\" inside this panel.",
  ];
}

function frameRules(storyboard) {
  return `FINAL STORY CHECK:
Draw the four panels in the frame's four white rounded panels in top-left, top-right, bottom-left, bottom-right reading order. Follow the storyboard's clear chronological cause-and-effect order.
Render exactly five new readable text blocks: four panel captions and one comic title.
The four panel captions read exactly:
${storyboard.panels.map(p => `- "${p.caption}"`).join("\n")}
All four captions must appear at matching upper-left positions inside their assigned panels.
Do not render any other new readable text, date, hashtag, logo, signature, sound effect, or label.
All phones, screens, chats, documents, signs, clocks, and packaging must remain text-free.
Keep the ending faithful to the diary.

FIXED POSTCARD FRAME LOCK — HIGHEST LAYOUT PRIORITY:
The second attached image is the fixed 2:3 postcard frame. Your output is that same frame with the four panels filled in.
Keep the frame layout identical: the same mint background, the same four white rounded panels at the same positions and sizes with the same thick black outlines, the same top text, the same planet and sparkles, the spiky green creature, the white bird with a cane, the white puppy, the QR code, and "${CREATOR_HANDLE}" at the bottom left.
Never move, redraw, restyle, recolor, resize, crop, or cover any of those frame elements.
Draw each panel's scene only inside the white interior of its rounded panel, on a white panel background. Keep every character, object, motion mark, and caption fully inside that white interior and clear of the black outline.
Leave the panel corners where the frame creatures overlap (upper-right of the top-right panel under the planet's legs, lower-left of the top-left panel, lower-left of the bottom-left panel, lower-right of the bottom-right panel) free of scene details and text. The planet covers the upper-right corner of the top-right panel, so the top-right panel's caption must end well before that corner.

PROTAGONIST CHECK:
Before rendering, confirm the protagonist has the reference's outlined curly loop hair (white inside, not filled black, round head outline visible) in every panel where they appear, and that no other person has curly loop hair. A bald protagonist, a protagonist with solid black filled hair, or a second person with curly loops is wrong.

LETTERING:
Write all four captions and the comic title in ${FRAME_LETTERING}. Use the same letter size, stroke weight, and color for all four captions.
Do not add any grid border, separator, stick, footer band, extra puppy, or other decoration.

COMIC TITLE:
Handwrite exactly "# ${storyboard.title}" once, in pure black, on the mint background below the bottom-left panel: left-aligned with the left edge of the bottom-left panel, on its own line directly above "${CREATOR_HANDLE}", to the right of the white bird's feet and left of the QR code.
Make it about the same height as the frame's top text "우아한테크코스 8기 데모데이", in the same rounded lettering with the same stroke weight, never bolder or blockier than that top text. Never place the title inside a panel or above the panels.`;
}

export function renderFrameFinalTask(storyboard) {
  const lines = [...storyOverview(storyboard), ...scenePlan(storyboard.scenePlan)];
  storyboard.panels.forEach((p, i) => {
    lines.push(...panel(p, i));
    if (i < 3) lines.push("");
  });
  return "[Final Task]\n" + lines.join("\n") + "\n\n" + frameRules(storyboard);
}
