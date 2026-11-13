// OpenAI 대체 생성. 키는 서버 .env.event에만 둔다.
// - 이미지: 백엔드 OpenAiDiaryImageGenerator와 같은 /v1/images/edits JSON 요청. 참조 이미지와 엽서 프레임을 함께 보낸다.
// - 스토리보드: Responses API에 Gemini와 같은 시스템 프롬프트·요청문·JSON 스키마를 보낸다(기본 gpt-6-luna).
import { config } from "./config.mjs";
import { storyboardSchema } from "./storyboard.mjs";
import { GenerationError } from "./vertex.mjs";

const ENDPOINT = "https://api.openai.com/v1/images/edits";
const MAX_PROMPT_LENGTH = 32_000;
// 2:3 엽서 비율에 맞는 OpenAI 지원 크기
const SIZE = "1024x1536";

const dataUrl = (mimeType, data) => `data:${mimeType};base64,${data.toString("base64")}`;

export function buildOpenAiPrompt({ stylePrompt, referenceInstruction, frameInstruction, finalTask }) {
  const prompt = [stylePrompt.trim(), referenceInstruction, frameInstruction, finalTask].join("\n\n");
  if (prompt.length > MAX_PROMPT_LENGTH) {
    throw new GenerationError("INVALID_REQUEST", `OpenAI 프롬프트가 ${MAX_PROMPT_LENGTH}자를 넘습니다: ${prompt.length}`);
  }
  return prompt;
}

export async function callOpenAiFrameImage({ stylePrompt, referencePng, frameImage, finalTask, referenceInstruction, frameInstruction },
  { fetchImpl = fetch } = {}) {
  const model = config.openaiImageModel;
  const body = {
    model,
    prompt: buildOpenAiPrompt({ stylePrompt, referenceInstruction, frameInstruction, finalTask }),
    // 지시문의 "Image 1 / Image 2" 순서와 맞춘다
    images: [
      { image_url: dataUrl("image/png", referencePng) },
      { image_url: dataUrl(frameImage.mimeType, frameImage.data) },
    ],
    n: 1,
    size: SIZE,
    quality: config.openaiImageQuality,
    output_format: "png",
  };
  let res;
  try {
    res = await fetchImpl(ENDPOINT, {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${config.openaiApiKey}` },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(config.openaiImageTimeoutMs),
    });
  } catch (error) {
    if (error.name === "TimeoutError") throw new GenerationError("TIMEOUT", `${model} 시간 초과`, { retryable: true });
    throw new GenerationError("PROVIDER_UNAVAILABLE", `${model} 호출 실패: ${error.message}`, { retryable: true });
  }
  const json = await res.json().catch(() => ({}));
  if (!res.ok) {
    // 오류 본문의 message는 요청 내용을 담을 수 있어 code·type만 남긴다
    const reason = json.error?.code ?? json.error?.type ?? "";
    if (reason === "moderation_blocked" || reason === "content_policy_violation") {
      throw new GenerationError("CONTENT_BLOCKED", `${model} 안전 정책 차단: ${reason}`);
    }
    throw new GenerationError(res.status === 429 ? "RATE_LIMITED" : "PROVIDER_ERROR", `${model} HTTP ${res.status}: ${reason}`,
      { retryable: res.status === 429 || res.status >= 500 });
  }
  const b64 = json.data?.[0]?.b64_json;
  if (!b64) throw new GenerationError("INVALID_RESPONSE", `${model} 이미지 응답이 없습니다.`, { retryable: true });
  return Buffer.from(b64, "base64");
}

const RESPONSES_ENDPOINT = "https://api.openai.com/v1/responses";

/** Gemini 전용 키워드(propertyOrdering)를 빼고, Structured Outputs strict 모드에 맞게 additionalProperties: false를 붙인다. */
export function openAiStoryboardSchema(node = storyboardSchema) {
  if (Array.isArray(node)) return node.map(openAiStoryboardSchema);
  if (!node || typeof node !== "object") return node;
  const out = {};
  for (const [key, value] of Object.entries(node)) {
    if (key === "propertyOrdering") continue;
    out[key] = key === "properties"
      ? Object.fromEntries(Object.entries(value).map(([k, v]) => [k, openAiStoryboardSchema(v)]))
      : openAiStoryboardSchema(value);
  }
  if (out.type === "object") out.additionalProperties = false;
  return out;
}

export function openAiStoryboardPayload(systemPrompt, requestText) {
  return {
    model: config.openaiStoryboardModel,
    instructions: systemPrompt,
    input: requestText,
    reasoning: { effort: config.openaiStoryboardReasoning },
    max_output_tokens: 16_384,
    text: { format: { type: "json_schema", name: "storyboard", strict: true, schema: openAiStoryboardSchema() } },
    store: false,
  };
}

/** 성공 시 스토리보드 JSON 문자열을 돌려준다. 검증은 parseStoryboard가 한다. */
export async function callOpenAiStoryboard(systemPrompt, requestText, { fetchImpl = fetch } = {}) {
  const model = config.openaiStoryboardModel;
  let res;
  try {
    res = await fetchImpl(RESPONSES_ENDPOINT, {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${config.openaiApiKey}` },
      body: JSON.stringify(openAiStoryboardPayload(systemPrompt, requestText)),
      signal: AbortSignal.timeout(config.openaiStoryboardTimeoutMs),
    });
  } catch (error) {
    if (error.name === "TimeoutError") throw new GenerationError("TIMEOUT", `${model} 시간 초과`, { retryable: true });
    throw new GenerationError("PROVIDER_UNAVAILABLE", `${model} 호출 실패: ${error.message}`, { retryable: true });
  }
  const json = await res.json().catch(() => ({}));
  if (!res.ok) {
    const reason = json.error?.code ?? json.error?.type ?? "";
    throw new GenerationError(res.status === 429 ? "RATE_LIMITED" : "PROVIDER_ERROR", `${model} HTTP ${res.status}: ${reason}`,
      { retryable: res.status === 429 || res.status >= 500 });
  }
  const content = (json.output ?? []).filter(item => item.type === "message").flatMap(item => item.content ?? []);
  if (content.some(part => part.type === "refusal")) throw new GenerationError("CONTENT_BLOCKED", `${model} 응답 거절`);
  if (json.status === "incomplete") {
    throw new GenerationError("INVALID_RESPONSE", `${model} 응답이 끝나지 않았습니다: ${json.incomplete_details?.reason ?? ""}`, { retryable: true });
  }
  const text = content.filter(part => part.type === "output_text").map(part => part.text).join("");
  if (!text.trim()) throw new GenerationError("INVALID_RESPONSE", `${model} 스토리보드 응답 본문이 비어 있습니다.`, { retryable: true });
  return text;
}
