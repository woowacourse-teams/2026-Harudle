// Vertex AI generateContent 호출. 인증은 GOOGLE_APPLICATION_CREDENTIALS(WIF)로 받는다.
import { config } from "./config.mjs";
import { storyboardSchema } from "./storyboard.mjs";

let authClient;
async function authHeader() {
  if (!authClient) {
    const { GoogleAuth } = await import("google-auth-library");
    authClient = await new GoogleAuth({ scopes: ["https://www.googleapis.com/auth/cloud-platform"] }).getClient();
  }
  return `Bearer ${(await authClient.getAccessToken()).token}`;
}

export class GenerationError extends Error {
  constructor(code, message, { retryable = false } = {}) {
    super(message);
    this.code = code;
    this.retryable = retryable;
  }
}

async function generateContent(model, body, timeoutMs) {
  const url = `https://aiplatform.googleapis.com/v1/projects/${config.project}/locations/${config.location}`
    + `/publishers/google/models/${model}:generateContent`;
  let res;
  try {
    res = await fetch(url, {
      method: "POST",
      headers: { "content-type": "application/json", authorization: await authHeader() },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(timeoutMs),
    });
  } catch (error) {
    if (error.name === "TimeoutError") throw new GenerationError("TIMEOUT", `${model} 시간 초과`, { retryable: true });
    throw new GenerationError("PROVIDER_UNAVAILABLE", `${model} 호출 실패: ${error.message}`, { retryable: true });
  }
  const json = await res.json().catch(() => ({}));
  if (!res.ok) {
    const retryable = res.status === 429 || res.status >= 500;
    throw new GenerationError(res.status === 429 ? "RATE_LIMITED" : "PROVIDER_ERROR",
      `${model} HTTP ${res.status}: ${json.error?.status ?? ""}`, { retryable });
  }
  const candidate = json.candidates?.[0];
  if (json.promptFeedback?.blockReason || ["SAFETY", "PROHIBITED_CONTENT", "IMAGE_SAFETY", "BLOCKLIST"]
    .includes(candidate?.finishReason)) {
    throw new GenerationError("CONTENT_BLOCKED", `${model} 안전 정책 차단: ${candidate?.finishReason ?? json.promptFeedback?.blockReason}`);
  }
  return candidate;
}

export async function callStoryboard(systemPrompt, requestText) {
  const candidate = await generateContent(config.storyboardModel, {
    systemInstruction: { parts: [{ text: systemPrompt }] },
    contents: [{ role: "user", parts: [{ text: requestText }] }],
    generationConfig: {
      responseMimeType: "application/json",
      responseJsonSchema: storyboardSchema,
      maxOutputTokens: 8192,
      candidateCount: 1,
      thinkingConfig: { thinkingLevel: config.storyboardThinkingLevel },
    },
  }, config.storyboardTimeoutMs);
  const text = candidate?.content?.parts?.filter(p => p.text && !p.thought).map(p => p.text).join("");
  if (!text) throw new GenerationError("INVALID_RESPONSE", "스토리보드 응답 본문이 비어 있습니다.", { retryable: true });
  return text;
}

export async function callFrameImage({ stylePrompt, referencePng, frameImage, finalTask, referenceInstruction, frameInstruction }) {
  const candidate = await generateContent(config.imageModel, {
    systemInstruction: { parts: [{ text: stylePrompt }] },
    contents: [{ role: "user", parts: [
      { text: referenceInstruction },
      { inlineData: { mimeType: "image/png", data: referencePng.toString("base64") } },
      { text: frameInstruction },
      { inlineData: { mimeType: frameImage.mimeType, data: frameImage.data.toString("base64") } },
      { text: finalTask },
    ] }],
    generationConfig: { responseModalities: ["TEXT", "IMAGE"], imageConfig: { aspectRatio: "2:3" } },
  }, config.imageTimeoutMs);
  const part = candidate?.content?.parts?.find(p => p.inlineData?.data);
  if (!part) throw new GenerationError("INVALID_RESPONSE", "이미지 응답이 없습니다.", { retryable: true });
  return Buffer.from(part.inlineData.data, "base64");
}
