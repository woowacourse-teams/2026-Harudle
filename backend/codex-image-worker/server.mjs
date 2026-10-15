import { createServer } from "node:http";
import { timingSafeEqual } from "node:crypto";
import { pathToFileURL } from "node:url";
import { MAX_BYTES, WorkerError, validateInput, validatePng } from "./image.mjs";

export function createWorkerServer({ token, generate, timeoutMs = 300000, log = console.log }) {
  if (typeof token !== "string" || !/^[!-~]{32,}$/.test(token)) throw new Error("Worker token must have at least 32 ASCII characters");
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1000 || timeoutMs > 600000) throw new Error("Invalid worker timeout");
  let active;
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
    if (active) {
      response.writeHead(429, { "Content-Type": "application/json" }).end('{"error":"worker_busy"}');
      return;
    }
    const controller = new AbortController();
    active = controller;
    const started = Date.now();
    let status = 502;
    const timeout = setTimeout(() => controller.abort(new WorkerError(504, "generation_timeout")), timeoutMs);
    const disconnected = () => {
      if (!response.writableEnded) controller.abort(new WorkerError(499, "client_disconnected"));
    };
    response.once("close", disconnected);
    request.once("aborted", disconnected);
    try {
      if (!/^application\/json(?:;|$)/i.test(request.headers["content-type"] || "")) {
        throw new WorkerError(415, "json_required");
      }
      if (Number(request.headers["content-length"]) > MAX_BYTES) throw new WorkerError(413, "request_too_large");
      const chunks = [];
      let length = 0;
      for await (const chunk of request) {
        length += chunk.length;
        if (length > MAX_BYTES) throw new WorkerError(413, "request_too_large");
        controller.signal.throwIfAborted();
        chunks.push(chunk);
      }
      let input;
      try { input = JSON.parse(Buffer.concat(chunks).toString("utf8")); }
      catch { throw new WorkerError(400, "invalid_json"); }
      const prepared = validateInput(input);
      const image = validatePng(await generate(prepared, controller.signal), prepared.reference);
      controller.signal.throwIfAborted();
      status = 200;
      response.writeHead(status, { "Content-Type": "image/png", "Content-Length": image.length, "Cache-Control": "no-store" });
      response.end(image);
    } catch (error) {
      const failure = controller.signal.aborted ? controller.signal.reason : error;
      status = failure instanceof WorkerError ? failure.status : 502;
      if (!response.destroyed && !response.writableEnded) {
        response.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-store" })
          .end(JSON.stringify({ error: failure instanceof WorkerError ? failure.code : "generation_failed" }));
      }
    } finally {
      clearTimeout(timeout);
      response.off("close", disconnected);
      request.off("aborted", disconnected);
      active = undefined;
      log(JSON.stringify({ event: "image_generation", status, elapsedMs: Date.now() - started }));
    }
  });
  server.requestTimeout = timeoutMs + 10000;
  server.headersTimeout = 10000;
  // Includes a stalled request body and a client that stops reading.
  server.setTimeout(timeoutMs + 10000, socket => socket.destroy());
  server.abortActive = () => active?.abort(new WorkerError(503, "worker_stopping"));
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const { createCodexGenerator } = await import("./generator.mjs");
  const generate = await createCodexGenerator();
  const server = createWorkerServer({
    token: process.env.CODEX_IMAGE_WORKER_TOKEN, generate,
    timeoutMs: Number(process.env.CODEX_IMAGE_TIMEOUT_MS || 300000),
  });
  const port = Number(process.env.PORT || 8090);
  server.listen(port, process.env.HOST || "127.0.0.1", () => {
    console.log(JSON.stringify({ event: "listening", port }));
  });
  for (const event of ["SIGTERM", "SIGINT"]) process.once(event, () => {
    server.abortActive();
    void generate.close?.();
    server.close();
    setTimeout(() => process.exit(1), 10000).unref();
  });
}
