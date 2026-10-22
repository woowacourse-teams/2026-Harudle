import { test } from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import { request as httpRequest } from "node:http";
import { setTimeout as delay } from "node:timers/promises";
import { createWorkerServer, workerSettings } from "./server.mjs";
import { sdkEnvironment } from "./generator.mjs";
import { WorkerError } from "./image.mjs";

const token = "test-internal-worker-token-32-characters";
const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a0f8AAAAASUVORK5CYII=", "base64");
const alternatePng = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQEBAI+Bq3kAAAAASUVORK5CYII=", "base64");
const input = {
  systemInstruction: "스타일 지시문\n원문 그대로",
  referenceInstruction: "[The Only Style Reference]\nUse this exact reference.",
  finalTask: "[Final Task]\n제목과 캡션을 보존",
  reference: { mediaType: "image/png", base64: Buffer.from("reference bytes").toString("base64") },
};
async function start(t, generate, options = {}) {
  const events = [];
  const observers = new Set();
  const server = createWorkerServer({ token, generate, ...options, log(value) {
    const entry = JSON.parse(value);
    events.push(entry);
    for (const observer of observers) observer(entry);
    options.log?.(value);
  } });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  t.after(() => {
    const stopped = server.abortActive();
    server.close();
    server.closeAllConnections();
    return stopped;
  });
  const url = "http://127.0.0.1:" + server.address().port + "/v1/images/generations";
  const request = (body = input, headers = {}, signal) => fetch(url, {
    method: "POST", headers: { "Content-Type": "application/json", Authorization: "Bearer " + token, ...headers },
    body: JSON.stringify(body), signal,
  });
  request.server = server;
  request.url = url;
  request.events = events;
  request.waitForEvent = predicate => {
    const existing = events.find(predicate);
    if (existing) return Promise.resolve(existing);
    return new Promise(resolve => {
      const observer = entry => {
        if (!predicate(entry)) return;
        observers.delete(observer);
        resolve(entry);
      };
      observers.add(observer);
    });
  };
  return request;
}

function deferred() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
}

async function untilReleased(release, signal) {
  signal.throwIfAborted();
  let aborted;
  const cancellation = new Promise((_, reject) => {
    aborted = () => reject(signal.reason);
    signal.addEventListener("abort", aborted, { once: true });
  });
  try { await Promise.race([release.promise, cancellation]); }
  finally { signal.removeEventListener("abort", aborted); }
}

const taggedInput = tag => ({ ...input, finalTask: tag });
const eventIs = (event, requestId) => entry => entry.event === event && entry.requestId === requestId;

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

test("queues requests in order without duplicating or swapping generation results", { timeout: 10000 }, async t => {
  const release = deferred();
  const calls = [];
  const request = await start(t, async (prepared, signal) => {
    const tag = prepared.prompt.split("\n\n").at(-1);
    calls.push(tag);
    if (tag === "first") await untilReleased(release, signal);
    return tag === "second" ? alternatePng : png;
  }, { queueCapacity: 2 });
  const first = request(taggedInput("first"));
  await request.waitForEvent(eventIs("image_generation_started", 1));
  const second = request(taggedInput("second"));
  await request.waitForEvent(eventIs("image_generation_queued", 2));
  const third = request(taggedInput("third"));
  await request.waitForEvent(eventIs("image_generation_queued", 3));
  assert.deepEqual(calls, ["first"]);
  release.resolve();
  const responses = await Promise.all([first, second, third]);
  assert.deepEqual(responses.map(response => response.status), [200, 200, 200]);
  const images = await Promise.all(responses.map(async response => Buffer.from(await response.arrayBuffer())));
  assert.deepEqual(images, [png, alternatePng, png]);
  assert.deepEqual(calls, ["first", "second", "third"]);
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

test("uses configurable execution and queue limits without changing the default timeout", () => {
  assert.deepEqual(workerSettings({ CODEX_IMAGE_WORKER_TOKEN: token }), {
    token, timeoutMs: 300000, concurrency: 1, queueCapacity: 1,
  });
  assert.deepEqual(workerSettings({ CODEX_IMAGE_WORKER_TOKEN: token,
    CODEX_IMAGE_CONCURRENCY: "2", CODEX_IMAGE_QUEUE_CAPACITY: "3", CODEX_IMAGE_TIMEOUT_MS: "2000" }), {
    token, timeoutMs: 2000, concurrency: 2, queueCapacity: 3,
  });
  for (const concurrency of [0, -1, 1.5, NaN, Infinity]) {
    assert.throws(() => createWorkerServer({ token, generate() {}, concurrency }), /concurrency/);
  }
  for (const queueCapacity of [-1, 0.5, NaN, Infinity, Number.MAX_SAFE_INTEGER]) {
    assert.throws(() => createWorkerServer({ token, generate() {}, queueCapacity }), /queue capacity/);
  }
  assert.throws(() => workerSettings({ CODEX_IMAGE_WORKER_TOKEN: token, CODEX_IMAGE_CONCURRENCY: "wrong" }), /concurrency/);
});

test("runs only the configured number of generations and rejects overflow with 503", { timeout: 10000 }, async t => {
  const release = deferred();
  const bothStarted = deferred();
  let calls = 0;
  let running = 0;
  let peak = 0;
  const request = await start(t, async (_, signal) => {
    calls++;
    running++;
    peak = Math.max(peak, running);
    if (calls === 2) bothStarted.resolve();
    try { await untilReleased(release, signal); return png; }
    finally { running--; }
  }, { concurrency: 2, queueCapacity: 1 });
  const first = request();
  const second = request();
  await bothStarted.promise;
  const third = request();
  await request.waitForEvent(eventIs("image_generation_queued", 3));
  const overflow = await request();
  assert.equal(overflow.status, 503);
  assert.deepEqual(await overflow.json(), { error: "worker_queue_full" });
  assert.equal(calls, 2);
  assert.equal((await request(input, { Authorization: "Bearer wrong" })).status, 401);
  assert.equal((await fetch(new URL("/health", request.url))).status, 200);
  release.resolve();
  assert.deepEqual((await Promise.all([first, second, third])).map(response => response.status), [200, 200, 200]);
  assert.equal(calls, 3);
  assert.equal(peak, 2);
});

test("can disable waiting with a zero queue capacity", { timeout: 10000 }, async t => {
  const release = deferred();
  const request = await start(t, async (_, signal) => {
    await untilReleased(release, signal);
    return png;
  }, { queueCapacity: 0 });
  const first = request();
  await request.waitForEvent(eventIs("image_generation_started", 1));
  assert.equal((await request()).status, 503);
  release.resolve();
  assert.equal((await first).status, 200);
});

test("a failed generation releases its slot and the next queued request still succeeds", { timeout: 10000 }, async t => {
  const release = deferred();
  let calls = 0;
  const request = await start(t, async (_, signal) => {
    if (++calls > 1) return png;
    await untilReleased(release, signal);
    throw new WorkerError(502, "native_image_generation_unavailable");
  });
  const first = request();
  await request.waitForEvent(eventIs("image_generation_started", 1));
  const second = request();
  await request.waitForEvent(eventIs("image_generation_queued", 2));
  release.resolve();
  assert.equal((await first).status, 502);
  assert.equal((await second).status, 200);
  assert.equal(calls, 2);
});

test("removes a disconnected queued request and immediately makes room for another", { timeout: 10000 }, async t => {
  const release = deferred();
  const calls = [];
  const request = await start(t, async (prepared, signal) => {
    const tag = prepared.prompt.split("\n\n").at(-1);
    calls.push(tag);
    if (tag === "first") await untilReleased(release, signal);
    return png;
  });
  const first = request(taggedInput("first"));
  await request.waitForEvent(eventIs("image_generation_started", 1));
  const controller = new AbortController();
  const cancelled = request(taggedInput("cancelled"), {}, controller.signal);
  const rejected = assert.rejects(cancelled, { name: "AbortError" });
  await request.waitForEvent(eventIs("image_generation_queued", 2));
  controller.abort();
  await rejected;
  const ended = await request.waitForEvent(eventIs("image_generation", 2));
  assert.equal(ended.status, 499);
  const third = request(taggedInput("third"));
  await request.waitForEvent(eventIs("image_generation_queued", 3));
  release.resolve();
  assert.deepEqual((await Promise.all([first, third])).map(response => response.status), [200, 200]);
  assert.deepEqual(calls, ["first", "third"]);
});

test("expires queued requests without starting Codex while cancelled work is still cleaning up", { timeout: 10000 }, async t => {
  const cleanup = deferred();
  t.after(() => cleanup.resolve());
  let calls = 0;
  const request = await start(t, async (_, signal) => {
    if (++calls > 1) return png;
    await new Promise(resolve => signal.addEventListener("abort", resolve, { once: true }));
    await cleanup.promise;
    signal.throwIfAborted();
  }, { timeoutMs: 1000 });
  const first = request();
  await request.waitForEvent(eventIs("image_generation_started", 1));
  const second = request();
  const response = await second;
  assert.equal(response.status, 504);
  assert.deepEqual(await response.json(), { error: "queue_timeout" });
  assert.equal(calls, 1);
  cleanup.resolve();
  assert.equal((await first).status, 504);
  assert.equal((await request()).status, 200);
  const timedOut = await request.waitForEvent(eventIs("image_generation", 2));
  assert.equal(timedOut.generationMs, 0);
});

test("includes queue time in the original deadline instead of resetting it at generation start", { timeout: 10000 }, async t => {
  const release = deferred();
  let calls = 0;
  const request = await start(t, async (_, signal) => {
    if (++calls === 1) { await untilReleased(release, signal); return png; }
    await new Promise((_, reject) => signal.addEventListener("abort", () => reject(signal.reason), { once: true }));
  }, { timeoutMs: 1000 });
  const first = request();
  await request.waitForEvent(eventIs("image_generation_started", 1));
  const second = request();
  await request.waitForEvent(eventIs("image_generation_queued", 2));
  await delay(650);
  release.resolve();
  assert.equal((await first).status, 200);
  const response = await second;
  assert.equal(response.status, 504);
  assert.deepEqual(await response.json(), { error: "generation_timeout" });
  const timing = await request.waitForEvent(eventIs("image_generation", 2));
  assert.ok(timing.queueMs > timing.generationMs, JSON.stringify(timing));
});

test("counts stalled request bodies toward capacity and expires them without invoking Codex", { timeout: 10000 }, async t => {
  let calls = 0;
  const request = await start(t, async () => { calls++; return png; }, { timeoutMs: 1000, queueCapacity: 0 });
  const upload = httpRequest(request.url, { method: "POST", headers: {
    Authorization: "Bearer " + token, "Content-Type": "application/json", "Content-Length": "100",
  } });
  t.after(() => upload.destroy());
  const responseReady = once(upload, "response");
  upload.write("{");
  await request.waitForEvent(eventIs("image_generation_accepted", 1));
  assert.equal((await request()).status, 503);
  const [response] = await responseReady;
  assert.equal(response.statusCode, 504);
  const chunks = [];
  for await (const chunk of response) chunks.push(chunk);
  assert.deepEqual(JSON.parse(Buffer.concat(chunks)), { error: "request_timeout" });
  assert.equal(calls, 0);
  assert.equal((await request()).status, 200);
});

test("cancelling one parallel request does not cancel the others", { timeout: 10000 }, async t => {
  const release = deferred();
  const signals = [];
  const bothStarted = deferred();
  const request = await start(t, async (_, signal) => {
    signals.push(signal);
    if (signals.length === 2) bothStarted.resolve();
    await untilReleased(release, signal);
    return png;
  }, { concurrency: 2 });
  const controller = new AbortController();
  const first = request(input, {}, controller.signal);
  const rejected = assert.rejects(first, { name: "AbortError" });
  await request.waitForEvent(eventIs("image_generation_started", 1));
  const second = request();
  await bothStarted.promise;
  controller.abort();
  await rejected;
  await request.waitForEvent(eventIs("image_generation", 1));
  assert.equal(signals[0].aborted, true);
  assert.equal(signals[1].aborted, false);
  release.resolve();
  assert.equal((await second).status, 200);
});

test("holds a cancelled execution slot until generation cleanup finishes and discards its late image", { timeout: 10000 }, async t => {
  const release = deferred();
  t.after(() => release.resolve());
  const aborted = deferred();
  let calls = 0;
  const request = await start(t, async (_, signal) => {
    if (++calls === 1) {
      signal.addEventListener("abort", () => aborted.resolve(), { once: true });
      await release.promise;
    }
    return png;
  });
  const controller = new AbortController();
  const first = request(input, {}, controller.signal);
  const rejected = assert.rejects(first, { name: "AbortError" });
  await request.waitForEvent(eventIs("image_generation_started", 1));
  controller.abort();
  await rejected;
  await aborted.promise;
  const second = request();
  await request.waitForEvent(eventIs("image_generation_queued", 2));
  assert.equal(calls, 1);
  release.resolve();
  assert.equal((await second).status, 200);
  assert.equal((await request.waitForEvent(eventIs("image_generation", 1))).status, 499);
  assert.equal(calls, 2);
});

test("shutdown cancels every running and queued request and prevents new generation", { timeout: 10000 }, async t => {
  const release = deferred();
  let calls = 0;
  const request = await start(t, async (_, signal) => {
    calls++;
    await untilReleased(release, signal);
    return png;
  }, { concurrency: 2 });
  const first = request();
  await request.waitForEvent(eventIs("image_generation_started", 1));
  const second = request();
  await request.waitForEvent(eventIs("image_generation_started", 2));
  const third = request();
  await request.waitForEvent(eventIs("image_generation_queued", 3));
  await request.server.abortActive();
  const responses = await Promise.all([first, second, third, request()]);
  assert.deepEqual(responses.map(response => response.status), [503, 503, 503, 503]);
  for (const response of responses) assert.deepEqual(await response.json(), { error: "worker_stopping" });
  assert.equal(calls, 2);
});

test("shutdown waits for cleanup before reporting that active work has stopped", { timeout: 10000 }, async t => {
  const cleanup = deferred();
  t.after(() => cleanup.resolve());
  const request = await start(t, async () => { await cleanup.promise; return png; });
  const first = request();
  await request.waitForEvent(eventIs("image_generation_started", 1));
  let stopped = false;
  const stopping = request.server.abortActive().then(() => { stopped = true; });
  await Promise.resolve();
  assert.equal(stopped, false);
  cleanup.resolve();
  await stopping;
  assert.equal((await first).status, 503);
});

test("logging failures do not change the result or leave execution slots occupied", async t => {
  const request = await start(t, async () => png, { log() { throw new Error("diagnostic failure"); } });
  assert.equal((await request()).status, 200);
  assert.equal((await request()).status, 200);
});
