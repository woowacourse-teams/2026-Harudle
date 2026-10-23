import { createServer } from "node:http";
import { timingSafeEqual } from "node:crypto";
import { performance } from "node:perf_hooks";
import { pathToFileURL } from "node:url";
import { MAX_BYTES, WorkerError, validateInput, validatePng } from "./image.mjs";

function validateSettings({ token, timeoutMs, concurrency, queueCapacity }) {
  if (typeof token !== "string" || !/^[!-~]{32,}$/.test(token)) throw new Error("Worker token must have at least 32 ASCII characters");
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1000 || timeoutMs > 600000) throw new Error("Invalid worker timeout");
  if (!Number.isSafeInteger(concurrency) || concurrency < 1) throw new Error("Invalid worker concurrency");
  if (!Number.isSafeInteger(queueCapacity) || queueCapacity < 0
      || !Number.isSafeInteger(concurrency + queueCapacity)) throw new Error("Invalid worker queue capacity");
}

export function workerSettings(env = process.env) {
  const settings = {
    token: env.CODEX_IMAGE_WORKER_TOKEN,
    timeoutMs: Number(env.CODEX_IMAGE_TIMEOUT_MS ?? 300000),
    concurrency: Number(env.CODEX_IMAGE_CONCURRENCY ?? 1),
    queueCapacity: Number(env.CODEX_IMAGE_QUEUE_CAPACITY ?? 1),
  };
  validateSettings(settings);
  return settings;
}

async function readInput(request, signal) {
  if (!/^application\/json(?:;|$)/i.test(request.headers["content-type"] || "")) {
    throw new WorkerError(415, "json_required");
  }
  if (Number(request.headers["content-length"]) > MAX_BYTES) throw new WorkerError(413, "request_too_large");
  const chunks = [];
  let length = 0;
  for await (const chunk of request) {
    length += chunk.length;
    if (length > MAX_BYTES) throw new WorkerError(413, "request_too_large");
    signal.throwIfAborted();
    chunks.push(chunk);
  }
  let input;
  try { input = JSON.parse(Buffer.concat(chunks).toString("utf8")); }
  catch { throw new WorkerError(400, "invalid_json"); }
  return validateInput(input);
}

async function abortableRead(request, signal) {
  let aborted;
  const cancellation = new Promise((_, reject) => {
    aborted = () => reject(signal.reason);
    signal.addEventListener("abort", aborted, { once: true });
  });
  try {
    signal.throwIfAborted();
    return await Promise.race([readInput(request, signal), cancellation]);
  } finally {
    signal.removeEventListener("abort", aborted);
  }
}

function sendError(response, error) {
  const status = error instanceof WorkerError ? error.status : 502;
  if (!response.destroyed && !response.writableEnded) {
    response.shouldKeepAlive = false;
    response.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-store" })
      .end(JSON.stringify({ error: error instanceof WorkerError ? error.code : "generation_failed" }));
  }
  return status;
}

function timeoutError(job) {
  const code = job.phase === "input" ? "request_timeout" : job.phase === "queue" ? "queue_timeout" : "generation_timeout";
  return new WorkerError(504, code);
}

export function createWorkerServer({
  token, generate, timeoutMs = 300000, concurrency = 1, queueCapacity = 1, log = console.log,
}) {
  validateSettings({ token, timeoutMs, concurrency, queueCapacity });
  const active = new Set();
  const requests = new Set();
  const pending = [];
  let stopping = false;
  let sequence = 0;
  const report = entry => {
    try { log(JSON.stringify(entry)); } catch { /* Diagnostics must not change a generation result. */ }
  };

  function checkActive(job) {
    if (!job.controller.signal.aborted && performance.now() >= job.deadline) {
      job.controller.abort(timeoutError(job));
    }
    job.controller.signal.throwIfAborted();
  }

  function drain() {
    while (!stopping && active.size < concurrency && pending.length) {
      const job = pending.shift();
      try { checkActive(job); } catch (error) { job.reject(error); continue; }
      job.controller.signal.removeEventListener("abort", job.abortQueue);
      job.startedAt = performance.now();
      job.phase = "generation";
      active.add(job);
      report({ event: "image_generation_started", requestId: job.id,
        queueMs: job.startedAt - job.queuedAt, active: active.size, queued: pending.length });
      job.resolve();
    }
  }

  function waitForTurn(job) {
    checkActive(job);
    job.phase = "queue";
    job.queuedAt = performance.now();
    return new Promise((resolve, reject) => {
      job.resolve = resolve;
      job.reject = reject;
      job.abortQueue = () => {
        const index = pending.indexOf(job);
        if (index >= 0) pending.splice(index, 1);
        reject(job.controller.signal.reason);
      };
      job.controller.signal.addEventListener("abort", job.abortQueue, { once: true });
      pending.push(job);
      report({ event: "image_generation_queued", requestId: job.id, active: active.size, queued: pending.length });
      drain();
    });
  }

  const expected = Buffer.from("Bearer " + token);
  const server = createServer(async (request, response) => {
    if (request.method === "GET" && request.url === "/health") {
      response.writeHead(200, { "Content-Type": "application/json" }).end('{"status":"ok"}');
      return;
    }
    const supplied = Buffer.from(request.headers.authorization || "");
    if (supplied.length !== expected.length || !timingSafeEqual(supplied, expected)) {
      response.writeHead(401).end();
      return;
    }
    if (request.method !== "POST" || request.url !== "/v1/images/generations") {
      response.writeHead(404).end();
      return;
    }
    // Reserve capacity before reading up to 20 MiB; uploads count toward the same bound.
    if (stopping || requests.size >= concurrency + queueCapacity) {
      const code = stopping ? "worker_stopping" : "worker_queue_full";
      sendError(response, new WorkerError(503, code));
      report({ event: "image_generation_rejected", status: 503, code, inFlight: requests.size });
      return;
    }
    const controller = new AbortController();
    const job = { id: ++sequence, controller, acceptedAt: performance.now(), phase: "input" };
    job.deadline = job.acceptedAt + timeoutMs;
    job.finished = new Promise(resolve => { job.finish = resolve; });
    requests.add(job);
    report({ event: "image_generation_accepted", requestId: job.id, inFlight: requests.size });
    let status = 502;
    let failureCode;
    const timeout = setTimeout(() => controller.abort(timeoutError(job)), timeoutMs);
    const disconnected = () => {
      if (!response.writableEnded) controller.abort(new WorkerError(499, "client_disconnected"));
    };
    response.once("close", disconnected);
    request.once("aborted", disconnected);
    try {
      const prepared = await abortableRead(request, controller.signal);
      await waitForTurn(job);
      checkActive(job);
      // Keep the execution slot until generate() has also finished its cleanup after cancellation.
      const image = validatePng(await generate(prepared, controller.signal), prepared.reference);
      checkActive(job);
      status = 200;
      response.writeHead(status, { "Content-Type": "image/png", "Content-Length": image.length, "Cache-Control": "no-store" });
      response.end(image);
    } catch (error) {
      const failure = controller.signal.aborted ? controller.signal.reason : error;
      status = sendError(response, failure);
      failureCode = failure instanceof WorkerError ? failure.code : "generation_failed";
    } finally {
      clearTimeout(timeout);
      response.off("close", disconnected);
      request.off("aborted", disconnected);
      controller.signal.removeEventListener("abort", job.abortQueue);
      requests.delete(job);
      active.delete(job);
      const endedAt = performance.now();
      report({ event: "image_generation", requestId: job.id, status, failureCode, phase: job.phase,
        elapsedMs: endedAt - job.acceptedAt,
        queueMs: job.queuedAt === undefined ? 0 : (job.startedAt ?? endedAt) - job.queuedAt,
        generationMs: job.startedAt === undefined ? 0 : endedAt - job.startedAt,
        active: active.size, queued: pending.length });
      job.finish();
      drain();
    }
  });
  server.requestTimeout = timeoutMs + 10000;
  server.headersTimeout = 10000;
  // Includes a stalled request body and a client that stops reading.
  server.setTimeout(timeoutMs + 10000, socket => socket.destroy());
  server.abortActive = () => {
    stopping = true;
    const admitted = [...requests];
    for (const job of admitted) job.controller.abort(new WorkerError(503, "worker_stopping"));
    return Promise.all(admitted.map(job => job.finished));
  };
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const settings = workerSettings();
  const { createCodexGenerator } = await import("./generator.mjs");
  const generate = await createCodexGenerator();
  const server = createWorkerServer({ ...settings, generate });
  const port = Number(process.env.PORT || 8090);
  server.listen(port, process.env.HOST || "127.0.0.1", () => {
    console.log(JSON.stringify({ event: "listening", port,
      concurrency: settings.concurrency, queueCapacity: settings.queueCapacity }));
  });
  for (const event of ["SIGTERM", "SIGINT"]) process.once(event, () => {
    const stopped = server.abortActive();
    server.close();
    const deadline = setTimeout(() => process.exit(1), 10000);
    deadline.unref();
    void stopped.then(() => generate.close?.()).then(() => clearTimeout(deadline)).catch(() => {
      console.error(JSON.stringify({ event: "worker_shutdown_failed" }));
      process.exitCode = 1;
    });
  });
}
