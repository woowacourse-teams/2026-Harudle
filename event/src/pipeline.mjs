// 일기 → 스토리보드 → 프레임 채우기 이미지 → 원본 프레임 합성
import { readFile } from "node:fs/promises";
import path from "node:path";
import { config } from "./config.mjs";
import { compositeOnFrame, CompositeError, loadFrame } from "./composite.mjs";
import { FRAME_INSTRUCTION, REFERENCE_INSTRUCTION, renderFrameFinalTask } from "./prompt.mjs";
import { parseStoryboard, renderStoryboardRequest } from "./storyboard.mjs";
import { callOpenAiFrameImage, callOpenAiStoryboard } from "./openai.mjs";
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

const STORYBOARD_PROVIDERS = {
  gemini: (assets, diary) => callStoryboard(assets.storyboardPrompt, renderStoryboardRequest(diary)),
  openai: (assets, diary) => callOpenAiStoryboard(assets.storyboardPrompt, renderStoryboardRequest(diary)),
};

/** 스토리보드 시도 계획. 기본은 Gemini 2번, 대체 생성을 켜면 Gemini 1번 + OpenAI 1번이다. */
export function storyboardProviderPlan(fallback = config.storyboardFallback) {
  return fallback === "openai" ? ["gemini", "openai"] : ["gemini", "gemini"];
}

async function makeStoryboard(assets, diary, { log, providers, plan }) {
  if (config.fakeAi && !providers) { await sleep(1500); return { storyboard: parseStoryboard(assets.fake.storyboard), provider: "fake" }; }
  const calls = providers ?? STORYBOARD_PROVIDERS;
  for (let attempt = 1; ; attempt++) {
    const provider = plan[attempt - 1];
    try {
      return { storyboard: parseStoryboard(await calls[provider](assets, diary)), provider };
    } catch (error) {
      const retryable = error instanceof GenerationError ? error.retryable : true; // JSON·검증 실패도 한 번 재시도
      log({ event: "storyboard_attempt_failed", attempt, provider, code: error.code ?? "INVALID_STORYBOARD", reason: error.message,
        next: retryable && attempt < plan.length ? plan[attempt] : null });
      if (attempt >= plan.length || !retryable) throw error;
    }
  }
}

function imageRequest(assets, storyboard) {
  return {
    stylePrompt: assets.stylePrompt,
    referencePng: assets.referencePng,
    frameImage: assets.frameImage,
    finalTask: renderFrameFinalTask(storyboard),
    referenceInstruction: REFERENCE_INSTRUCTION,
    frameInstruction: FRAME_INSTRUCTION,
  };
}

const PROVIDERS = {
  gemini: async (assets, request) => {
    if (config.fakeAi) { await sleep(2500); return assets.fake.generated; }
    return callFrameImage(request);
  },
  openai: async (assets, request) => {
    if (config.fakeAi) { await sleep(2500); return assets.fake.generated; }
    return callOpenAiFrameImage(request);
  },
};

/** 시도마다 쓸 이미지 제공자. 대체 생성을 켜면 마지막 시도만 OpenAI로 바꾼다. */
export function imageProviderPlan(attempts = config.imageAttempts, fallback = config.imageFallback) {
  const total = Math.max(1, attempts);
  const plan = Array(total).fill("gemini");
  if (fallback === "openai") plan[total - 1] = "openai";
  return plan;
}

/**
 * onStage(stage)로 진행 단계를 알린다. 성공 시 { title, png, attempts, provider, storyboardProvider }
 * Gemini 칸 위치 거절·429·시간 초과 같은 재시도 가능 실패는 다음 시도(대체 생성이면 OpenAI)로 넘긴다.
 */
export async function runPipeline(assets, diary, onStage, {
  log = () => {}, providers = PROVIDERS, plan = imageProviderPlan(),
  storyboardProviders, storyboardPlan = storyboardProviderPlan(),
} = {}) {
  onStage("storyboard");
  const { storyboard, provider: storyboardProvider } = await makeStoryboard(assets, diary,
    { log, providers: storyboardProviders, plan: storyboardPlan });
  const request = imageRequest(assets, storyboard);
  for (let attempt = 1; ; attempt++) {
    const provider = plan[attempt - 1];
    onStage("image");
    try {
      const generated = await providers[provider](assets, request);
      onStage("compose");
      const png = await compositeOnFrame(assets.frame, generated);
      return { title: storyboard.title, png, attempts: attempt, provider, storyboardProvider };
    } catch (error) {
      const retryable = error instanceof CompositeError || (error instanceof GenerationError && error.retryable);
      log({ event: "image_attempt_failed", attempt, provider, code: error.code ?? "COMPOSITE_REJECTED", reason: error.message,
        next: retryable && attempt < plan.length ? plan[attempt] : null });
      if (attempt >= plan.length || !retryable) throw error;
    }
  }
}
