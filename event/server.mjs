// 데모데이 엽서 이벤트 서버. Host Nginx 뒤에서 127.0.0.1로만 받는다.
import { createReadStream } from "node:fs";
import { readFile, stat } from "node:fs/promises";
import http from "node:http";
import path from "node:path";
import { config, validateConfig } from "./src/config.mjs";
import { JobQueue } from "./src/jobs.mjs";
import { loadAssets } from "./src/pipeline.mjs";

const log = entry => console.log(JSON.stringify({ time: new Date().toISOString(), ...entry }));

validateConfig();
const assets = await loadAssets();
const queue = new JobQueue(assets, log);
await queue.init();

const STATIC = {
  "/": ["index.html", "text/html; charset=utf-8"],
  "/frame-preview.webp": [null, "image/webp"],
};
const JOB_PATH = /^\/api\/jobs\/([0-9a-f-]{36})(\/image)?$/;
const MAX_BODY = 16 * 1024;

function send(res, status, body, headers = {}) {
  const json = typeof body === "string" ? body : JSON.stringify(body);
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...headers });
  res.end(json);
}

function clientIp(req) {
  // Host Nginx가 붙인 X-Real-IP만 신뢰한다. 서버는 127.0.0.1에만 바인딩한다.
  const fromProxy = req.headers["x-real-ip"];
  return (typeof fromProxy === "string" && fromProxy) || req.socket.remoteAddress || "unknown";
}

async function readJson(req) {
  let size = 0;
  const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > MAX_BODY) throw Object.assign(new Error("too large"), { status: 413 });
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString("utf8")); }
  catch { throw Object.assign(new Error("invalid json"), { status: 400 }); }
}

async function createJob(req, res) {
  const body = await readJson(req);
  if (config.accessCode && body.code !== config.accessCode) {
    return send(res, 403, { error: "ACCESS_DENIED", message: "행사장 QR 코드로 접속해 주세요." });
  }
  if (body.consent !== true) {
    return send(res, 400, { error: "CONSENT_REQUIRED", message: "안내 사항에 동의해 주세요." });
  }
  const diary = typeof body.diary === "string" ? body.diary.replace(/\r\n?/g, "\n").trim() : "";
  if ([...diary].length < config.diaryMinLength || [...diary].length > config.diaryMaxLength) {
    return send(res, 400, {
      error: "INVALID_DIARY",
      message: `일기는 ${config.diaryMinLength}자 이상 ${config.diaryMaxLength}자 이하로 적어 주세요.`,
    });
  }
  const result = queue.submit(diary, clientIp(req));
  if (result.error) return send(res, result.status, { error: result.error, message: result.message });
  send(res, 202, queue.get(result.job.id));
}

async function serveStatic(res, file, type) {
  const body = file ? await readFile(path.join(config.publicDir, file)) : assets.frameImage.data;
  res.writeHead(200, { "content-type": type, "cache-control": file ? "no-cache" : "public, max-age=3600" });
  res.end(body);
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://localhost");
  try {
    if (req.method === "GET" && url.pathname === "/health") return send(res, 200, { status: "ok", ...queue.stats() });
    if (req.method === "GET" && url.pathname === "/api/config") {
      return send(res, 200, {
        accessCodeRequired: Boolean(config.accessCode),
        diaryMinLength: config.diaryMinLength,
        diaryMaxLength: config.diaryMaxLength,
        resultTtlMinutes: config.resultTtlMs / 60_000,
      });
    }
    if (req.method === "POST" && url.pathname === "/api/jobs") return await createJob(req, res);
    const match = url.pathname.match(JOB_PATH);
    if (req.method === "GET" && match) {
      if (!match[2]) {
        const job = queue.get(match[1]);
        return job ? send(res, 200, job) : send(res, 404, { error: "NOT_FOUND", message: "만료되었거나 없는 작업이에요." });
      }
      const file = queue.resultPath(match[1]);
      if (!file) return send(res, 404, { error: "NOT_FOUND", message: "이미지가 없어요." });
      const { size } = await stat(file);
      const headers = { "content-type": "image/png", "content-length": size, "cache-control": "private, max-age=600" };
      if (url.searchParams.get("download") === "1") headers["content-disposition"] = 'attachment; filename="harudle-demoday.png"';
      res.writeHead(200, headers);
      return createReadStream(file).pipe(res);
    }
    const page = req.method === "GET" && STATIC[url.pathname];
    if (page) return await serveStatic(res, ...page);
    send(res, 404, { error: "NOT_FOUND" });
  } catch (error) {
    if (error.status) return send(res, error.status, { error: "BAD_REQUEST" });
    log({ event: "request_error", path: url.pathname, reason: error.message });
    if (!res.headersSent) send(res, 500, { error: "INTERNAL_ERROR" });
  }
});

server.requestTimeout = 15_000;
server.listen(config.port, config.host, () => {
  log({ event: "started", host: config.host, port: config.port, fakeAi: config.fakeAi, concurrency: config.concurrency });
});

for (const signal of ["SIGTERM", "SIGINT"]) {
  process.on(signal, () => {
    log({ event: "stopping", signal, ...queue.stats() });
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 5_000).unref();
  });
}
