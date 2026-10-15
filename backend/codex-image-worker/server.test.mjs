import { test } from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import { createWorkerServer } from "./server.mjs";
import { sdkEnvironment } from "./generator.mjs";
import { WorkerError } from "./image.mjs";

const token = "test-internal-worker-token-32-characters";
const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a0f8AAAAASUVORK5CYII=", "base64");
const input = {
  systemInstruction: "스타일 지시문\n원문 그대로",
  referenceInstruction: "[The Only Style Reference]\nUse this exact reference.",
  finalTask: "[Final Task]\n제목과 캡션을 보존",
  reference: { mediaType: "image/png", base64: Buffer.from("reference bytes").toString("base64") },
};
async function start(t, generate, options = {}) {
  const server = createWorkerServer({ token, generate, log() {}, ...options });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  t.after(() => { server.abortActive(); server.closeAllConnections(); server.close(); });
  const url = "http://127.0.0.1:" + server.address().port + "/v1/images/generations";
  return (body = input, headers = {}, signal) => fetch(url, {
    method: "POST", headers: { "Content-Type": "application/json", Authorization: "Bearer " + token, ...headers },
    body: JSON.stringify(body), signal,
  });
}

test("passes the complete prepared prompt and exact reference, returning binary PNG", async t => {
  let calls = 0;
  const request = await start(t, async prepared => {
    calls++;
    assert.equal(prepared.prompt, [input.systemInstruction, input.referenceInstruction, input.finalTask].join("\n\n"));
    assert.deepEqual(prepared.reference, Buffer.from(input.reference.base64, "base64"));
    return png;
  });
  const response = await request();
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("content-type"), "image/png");
  assert.deepEqual(Buffer.from(await response.arrayBuffer()), png);
  assert.equal(calls, 1);
});

test("requires the internal token before invoking Codex", async t => {
  let calls = 0;
  const request = await start(t, async () => { calls++; return png; });
  assert.equal((await request(input, { Authorization: "Bearer wrong" })).status, 401);
  assert.equal(calls, 0);
});

test("rejects malformed or unsupported inputs before generating", async t => {
  let calls = 0;
  const request = await start(t, async () => { calls++; return png; });
  assert.equal((await request({ ...input, finalTask: "" })).status, 400);
  assert.equal((await request({ ...input, reference: { mediaType: "image/svg+xml", base64: "YQ==" } })).status, 415);
  assert.equal((await request({ ...input, reference: { mediaType: "image/png", base64: "not-base64!" } })).status, 400);
  assert.equal((await request({ ...input, finalTask: "x".repeat(256 * 1024 + 1) })).status, 400);
  assert.equal((await request(input, { "Content-Type": "text/plain" })).status, 415);
  assert.equal(calls, 0);
});

test("does not queue or duplicate a generation when busy", async t => {
  let resolveStarted, finish;
  const started = new Promise(resolve => { resolveStarted = resolve; });
  const pending = new Promise(resolve => { finish = resolve; });
  let calls = 0;
  const request = await start(t, async () => { calls++; resolveStarted(); await pending; return png; });
  const first = request();
  await started;
  assert.equal((await request()).status, 429);
  assert.equal(calls, 1);
  finish();
  assert.equal((await first).status, 200);
  assert.equal((await request()).status, 200);
});

test("aborts timed out SDK work and releases its concurrency slot", async t => {
  let calls = 0;
  let aborted = false;
  const request = await start(t, async (_, signal) => {
    if (++calls > 1) return png;
    await new Promise((_, reject) => signal.addEventListener("abort", () => {
      aborted = true; reject(signal.reason);
    }, { once: true }));
  }, { timeoutMs: 1000 });
  assert.equal((await request()).status, 504);
  assert.equal(aborted, true);
  assert.equal((await request()).status, 200);
});

test("a client disconnect aborts the in-flight SDK turn", async t => {
  let resolveStarted, resolveAborted;
  const started = new Promise(resolve => { resolveStarted = resolve; });
  const aborted = new Promise(resolve => { resolveAborted = resolve; });
  const request = await start(t, async (_, signal) => {
    resolveStarted();
    await new Promise((_, reject) => signal.addEventListener("abort", () => {
      resolveAborted(); reject(signal.reason);
    }, { once: true }));
  });
  const controller = new AbortController();
  const response = request(input, {}, controller.signal).catch(() => {});
  await started;
  controller.abort();
  await aborted;
  await response;
});

test("does not return provider diagnostics or accept a copied reference as a new image", async t => {
  const request = await start(t, async () => { throw new Error("sensitive token and diary"); });
  const response = await request();
  assert.equal(response.status, 502);
  assert.deepEqual(await response.json(), { error: "generation_failed" });
  const copied = await start(t, async () => png);
  const identical = await copied({ ...input, reference: { mediaType: "image/png", base64: png.toString("base64") } });
  assert.equal(identical.status, 502);
});

test("maps explicit worker errors and rejects invalid generated bytes", async t => {
  const failed = await start(t, async () => { throw new WorkerError(502, "native_image_generation_unavailable"); });
  assert.equal((await failed()).status, 502);
  const invalid = await start(t, async () => Buffer.from("not an image"));
  assert.equal((await invalid()).status, 502);
});

test("SDK environment excludes worker secrets and API keys", () => {
  assert.deepEqual(sdkEnvironment({
    PATH: "/bin", CODEX_HOME: "/auth", CODEX_IMAGE_WORKER_TOKEN: token, CODEX_API_KEY: "secret",
    OPENAI_API_KEY: "secret", AWS_SECRET_ACCESS_KEY: "secret", DB_PASSWORD: "secret", CODEX_THREAD_ID: "parent",
  }), { PATH: "/bin", CODEX_HOME: "/auth" });
});
