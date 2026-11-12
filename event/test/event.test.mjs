import assert from "node:assert/strict";
import { test } from "node:test";
import { renderFrameFinalTask } from "../src/prompt.mjs";
import { parseStoryboard, renderStoryboardRequest } from "../src/storyboard.mjs";

const raw = {
  title: "바람 부는 날의 출근길",
  cast_continuity: "The protagonist, an office worker.",
  panels: ["setup", "action", "escalation", "resolution"].map((role, i) => ({
    panel_number: i + 1,
    story_role: role,
    caption: `자막 ${i + 1}`,
    scene: `Scene ${i + 1}.`,
    characters: "The protagonist walks.",
    emotion: "Calm",
    props: i === 3 ? ["umbrella", "bus seat"] : ["umbrella"],
  })),
  visual_plan: {
    environment_rule: "A windy morning street.",
    focal_colors: [{ prop: "Umbrella", component: "canopy fabric", color_hex: "#5D8AA8", panel_numbers: [1] }],
  },
};

test("스토리보드 응답을 백엔드 매퍼와 같은 규칙으로 변환한다", () => {
  const storyboard = parseStoryboard(JSON.stringify(raw));
  assert.equal(storyboard.title, "바람 부는 날의 출근길");
  assert.deepEqual(storyboard.scenePlan.focalColors[0].panelNumbers, [1, 2, 3, 4]);
});

test("역할 순서가 다르면 거절한다", () => {
  const broken = structuredClone(raw);
  broken.panels[0].story_role = "action";
  assert.throws(() => parseStoryboard(broken), /순서/);
});

test("색상 대상 소품이 어느 패널에도 없으면 거절한다", () => {
  const broken = structuredClone(raw);
  broken.visual_plan.focal_colors[0].prop = "hat";
  assert.throws(() => parseStoryboard(broken), /props/);
});

test("최종 지시문에 프레임 규칙, 자막 4개, 제목이 들어간다", () => {
  const task = renderFrameFinalTask(parseStoryboard(raw));
  assert.match(task, /^\[Final Task\]\nSELECTED STORY: 바람 부는 날의 출근길/);
  assert.match(task, /FIXED POSTCARD FRAME LOCK/);
  assert.match(task, /Handwrite exactly "# 바람 부는 날의 출근길" once/);
  for (let i = 1; i <= 4; i++) assert.match(task, new RegExp(`Caption reads exactly: "자막 ${i}"`));
  assert.doesNotMatch(task, /square 1:1 canvas|FINAL FOOTER LOCK/);
  assert.match(task, /upper-right of the top-right panel under the planet's legs/);
  assert.match(task, /LETTERING:\nWrite all four captions and the comic title in the same lettering style as the frame/);
  assert.match(task, /left 70% of the panel width/);
  assert.match(task, /CAST AND CONTINUITY:\n.*\n.*\nThe protagonist \(the diary writer/);
  assert.match(task, /PROTAGONIST CHECK:/);
});

test("일기 원문을 diary 태그 안에 넣는다", () => {
  assert.match(renderStoryboardRequest("오늘은 맑음"), /<diary>\n오늘은 맑음\n<\/diary>/);
});
