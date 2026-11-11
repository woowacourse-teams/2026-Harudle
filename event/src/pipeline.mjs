// 일기 → 스토리보드 → 프레임 채우기 이미지 → 원본 프레임 합성
import { readFile } from "node:fs/promises";
import path from "node:path";
import { config } from "./config.mjs";
import { compositeOnFrame, CompositeError, loadFrame } from "./composite.mjs";
import { FRAME_INSTRUCTION, REFERENCE_INSTRUCTION, renderFrameFinalTask } from "./prompt.mjs";
import { parseStoryboard, renderStoryboardRequest } from "./storyboard.mjs";
import { callFrameImage, callStoryboard, GenerationError } from "./vertex.mjs";

const read = name => readFile(path.join(config.assetsDir, name));

export async function loadAssets() {
  const frameName = await pickFrame();
  const frameData = await read(frameName);
  const assets = {
    storyboardPrompt: (await read("storyboard-prompt.txt")).toString("utf8"),
    stylePrompt: (await read("image-style.txt")).toString("utf8"),
    referencePng: await read("reference.png"),
    frameImage: { data: frameData, mimeType: frameName.endsWith(".png") ? "image/png" : "image/webp" },
    frame: await loadFrame(frameData),
  };
  if (/SEASONAL FRAME/i.test(assets.stylePrompt)) {
    throw new Error("image-style.txt에 테마 프레임(SEASONAL FRAME) 구간이 남아 있습니다. 이벤트 프레임과 충돌하니 제거하세요.");
  }
  if (config.fakeAi) {
    assets.fake = {
      storyboard: JSON.parse((await read("fake/storyboard.json")).toString("utf8")),
      generated: await read("fake/generated.png"),
    };
  }
  return assets;
}

async function pickFrame() {
  for (const name of ["frame.png", "frame.webp"]) {
    try { await read(name); return name; } catch { /* 다음 후보 */ }
  }
  throw new Error(`프레임 이미지가 없습니다: ${config.assetsDir}/frame.png 또는 frame.webp`);
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

async function makeStoryboard(assets, diary) {
  if (config.fakeAi) { await sleep(1500); return parseStoryboard(assets.fake.storyboard); }
  for (let attempt = 1; ; attempt++) {
    try {
      return parseStoryboard(await callStoryboard(assets.storyboardPrompt, renderStoryboardRequest(diary)));
    } catch (error) {
      const retryable = error instanceof GenerationError ? error.retryable : true; // JSON·검증 실패도 한 번 재시도
      if (attempt >= 2 || !retryable) throw error;
    }
  }
}

async function makeImage(assets, storyboard) {
  if (config.fakeAi) { await sleep(2500); return assets.fake.generated; }
  return callFrameImage({
    stylePrompt: assets.stylePrompt,
    referencePng: assets.referencePng,
    frameImage: assets.frameImage,
    finalTask: renderFrameFinalTask(storyboard),
    referenceInstruction: REFERENCE_INSTRUCTION,
    frameInstruction: FRAME_INSTRUCTION,
  });
}

/** onStage(stage)로 진행 단계를 알린다. 성공 시 { title, png } */
export async function runPipeline(assets, diary, onStage) {
  onStage("storyboard");
  const storyboard = await makeStoryboard(assets, diary);
  for (let attempt = 1; ; attempt++) {
    onStage("image");
    try {
      const generated = await makeImage(assets, storyboard);
      onStage("compose");
      const png = await compositeOnFrame(assets.frame, generated);
      return { title: storyboard.title, png, attempts: attempt };
    } catch (error) {
      const retryable = error instanceof CompositeError || (error instanceof GenerationError && error.retryable);
      if (attempt >= config.imageAttempts || !retryable) throw error;
    }
  }
}
