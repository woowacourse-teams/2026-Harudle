import assert from "node:assert/strict";
import { test } from "node:test";

// config는 import 시점에 환경 변수를 읽으므로 먼저 설정한다
process.env.EVENT_FAKE_AI = "true";
process.env.OPENAI_API_KEY = "sk-test-not-real";
const { imageProviderPlan, loadAssets, runPipeline, storyboardProviderPlan } = await import("../src/pipeline.mjs");
const { CompositeError } = await import("../src/composite.mjs");
const { callOpenAiFrameImage, callOpenAiStoryboard } = await import("../src/openai.mjs");
const { GenerationError } = await import("../src/vertex.mjs");

const assets = await loadAssets();

test("대체 생성을 켜면 전체 시도 수는 그대로 두고 마지막 시도만 OpenAI로 한다", () => {
  assert.deepEqual(imageProviderPlan(2, "none"), ["gemini", "gemini"]);
  assert.deepEqual(imageProviderPlan(2, "openai"), ["gemini", "openai"]);
  assert.deepEqual(imageProviderPlan(1, "openai"), ["openai"]);
});

test("Gemini 칸 위치 거절 후 OpenAI 결과로 엽서를 만들고 실패 시도를 기록한다", async () => {
  const logs = [];
  const result = await runPipeline(assets, "대체 생성 시험용 일기입니다", () => {}, {
    log: entry => logs.push(entry),
    plan: ["gemini", "openai"],
    providers: {
      gemini: async () => { throw new CompositeError("생성본 1번 칸 위치가 원본과 200px 다릅니다."); },
      openai: async () => assets.fake.generated,
    },
  });
  assert.equal(result.provider, "openai");
  assert.equal(result.attempts, 2);
  assert.deepEqual(logs.map(l => [l.provider, l.code, l.next]), [["gemini", "COMPOSITE_REJECTED", "openai"]]);
});

test("안전 정책 차단처럼 재시도할 수 없는 실패는 대체 생성으로 넘기지 않는다", async () => {
  let openaiCalled = false;
  await assert.rejects(runPipeline(assets, "대체 생성 시험용 일기입니다", () => {}, {
    plan: ["gemini", "openai"],
    providers: {
      gemini: async () => { throw new GenerationError("CONTENT_BLOCKED", "차단"); },
      openai: async () => { openaiCalled = true; return assets.fake.generated; },
    },
  }), { code: "CONTENT_BLOCKED" });
  assert.equal(openaiCalled, false);
});

const request = {
  stylePrompt: "STYLE", referencePng: Buffer.from("ref"), frameImage: { data: Buffer.from("frame"), mimeType: "image/webp" },
  finalTask: "[Final Task]", referenceInstruction: "[Image 1]", frameInstruction: "[Image 2]",
};

test("OpenAI 요청은 참조 이미지와 프레임을 순서대로 보내고 2:3 크기로 받는다", async () => {
  let sent;
  const png = await callOpenAiFrameImage(request, {
    fetchImpl: async (url, init) => {
      sent = { url, init, body: JSON.parse(init.body) };
      return new Response(JSON.stringify({ data: [{ b64_json: Buffer.from("out").toString("base64") }] }), { status: 200 });
    },
  });
  assert.equal(png.toString(), "out");
  assert.equal(sent.url, "https://api.openai.com/v1/images/edits");
  assert.equal(sent.init.headers.authorization, "Bearer sk-test-not-real");
  assert.equal(sent.body.size, "1024x1536");
  assert.equal(sent.body.model, "gpt-image-2.5-flare");
  assert.deepEqual(sent.body.images.map(i => i.image_url.slice(0, 16)), ["data:image/png;b", "data:image/webp;"]);
  assert.equal(sent.body.prompt, "STYLE\n\n[Image 1]\n\n[Image 2]\n\n[Final Task]");
});

test("OpenAI 429는 재시도 가능한 RATE_LIMITED로, 오류 본문 message는 남기지 않는다", async () => {
  const error = await callOpenAiFrameImage(request, {
    fetchImpl: async () => new Response(JSON.stringify({ error: { code: "rate_limit_exceeded", message: "secret detail" } }), { status: 429 }),
  }).catch(e => e);
  assert.equal(error.code, "RATE_LIMITED");
  assert.equal(error.retryable, true);
  assert.doesNotMatch(error.message, /secret detail/);
});

const storyboardJson = () => JSON.stringify(assets.fake.storyboard);

test("스토리보드 대체 생성을 켜면 Gemini 1번 + OpenAI 1번, 끄면 Gemini 2번이다", () => {
  assert.deepEqual(storyboardProviderPlan("none"), ["gemini", "gemini"]);
  assert.deepEqual(storyboardProviderPlan("openai"), ["gemini", "openai"]);
});

test("Gemini 스토리보드가 429로 실패하면 OpenAI 스토리보드로 엽서를 만든다", async () => {
  const logs = [];
  const result = await runPipeline(assets, "대체 생성 시험용 일기입니다", () => {}, {
    log: entry => logs.push(entry),
    storyboardPlan: ["gemini", "openai"],
    storyboardProviders: {
      gemini: async () => { throw new GenerationError("RATE_LIMITED", "429", { retryable: true }); },
      openai: async () => storyboardJson(),
    },
    plan: ["gemini"],
    providers: { gemini: async () => assets.fake.generated },
  });
  assert.equal(result.storyboardProvider, "openai");
  assert.deepEqual(logs.map(l => [l.event, l.provider, l.code, l.next]), [["storyboard_attempt_failed", "gemini", "RATE_LIMITED", "openai"]]);
});

test("스토리보드 JSON 검증 실패도 OpenAI로 넘기고, 안전 차단은 넘기지 않는다", async () => {
  const invalid = await runPipeline(assets, "대체 생성 시험용 일기입니다", () => {}, {
    storyboardPlan: ["gemini", "openai"],
    storyboardProviders: { gemini: async () => "{\"title\":\"\"}", openai: async () => storyboardJson() },
    plan: ["gemini"], providers: { gemini: async () => assets.fake.generated },
  });
  assert.equal(invalid.storyboardProvider, "openai");
  let openaiCalled = false;
  await assert.rejects(runPipeline(assets, "대체 생성 시험용 일기입니다", () => {}, {
    storyboardPlan: ["gemini", "openai"],
    storyboardProviders: {
      gemini: async () => { throw new GenerationError("CONTENT_BLOCKED", "차단"); },
      openai: async () => { openaiCalled = true; return storyboardJson(); },
    },
  }), { code: "CONTENT_BLOCKED" });
  assert.equal(openaiCalled, false);
});

test("OpenAI 스토리보드는 Responses API에 Gemini 전용 키워드를 뺀 strict 스키마로 보낸다", async () => {
  let sent;
  const text = await callOpenAiStoryboard("SYSTEM", "<diary>오늘</diary>", {
    fetchImpl: async (url, init) => {
      sent = { url, init, body: JSON.parse(init.body) };
      return new Response(JSON.stringify({ status: "completed", output: [
        { type: "reasoning", summary: [] },
        { type: "message", content: [{ type: "output_text", text: "{\"a\":1}" }] },
      ] }), { status: 200 });
    },
  });
  assert.equal(text, "{\"a\":1}");
  assert.equal(sent.url, "https://api.openai.com/v1/responses");
  assert.equal(sent.init.headers.authorization, "Bearer sk-test-not-real");
  assert.equal(sent.body.model, "gpt-6-luna");
  assert.equal(sent.body.instructions, "SYSTEM");
  assert.equal(sent.body.input, "<diary>오늘</diary>");
  assert.deepEqual(sent.body.reasoning, { effort: "low" });
  assert.equal(sent.body.store, false);
  const format = sent.body.text.format;
  assert.equal(format.type, "json_schema");
  assert.equal(format.strict, true);
  assert.doesNotMatch(JSON.stringify(format.schema), /propertyOrdering/);
  assert.equal(format.schema.additionalProperties, false);
  assert.equal(format.schema.properties.panels.items.additionalProperties, false);
});

test("OpenAI 스토리보드: 거절은 차단, 미완료는 재시도 가능 오류로 바꾸고 오류 본문 message는 남기지 않는다", async () => {
  const reply = body => async () => new Response(JSON.stringify(body), { status: 200 });
  const refused = await callOpenAiStoryboard("S", "U", { fetchImpl: reply({ status: "completed", output: [
    { type: "message", content: [{ type: "refusal", refusal: "안 됩니다" }] }] }) }).catch(e => e);
  assert.equal(refused.code, "CONTENT_BLOCKED");
  const incomplete = await callOpenAiStoryboard("S", "U", { fetchImpl: reply({ status: "incomplete",
    incomplete_details: { reason: "max_output_tokens" }, output: [] }) }).catch(e => e);
  assert.equal(incomplete.code, "INVALID_RESPONSE");
  assert.equal(incomplete.retryable, true);
  const limited = await callOpenAiStoryboard("S", "U", {
    fetchImpl: async () => new Response(JSON.stringify({ error: { code: "rate_limit_exceeded", message: "secret detail" } }), { status: 429 }),
  }).catch(e => e);
  assert.equal(limited.code, "RATE_LIMITED");
  assert.doesNotMatch(limited.message, /secret detail/);
});
