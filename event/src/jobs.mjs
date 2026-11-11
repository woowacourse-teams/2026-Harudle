// 메모리 대기열. 프로세스가 재시작되면 진행 중인 작업과 결과 목록은 사라진다(이벤트용 단일 인스턴스 전제).
import { randomUUID } from "node:crypto";
import { mkdir, readdir, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { config } from "./config.mjs";
import { runPipeline } from "./pipeline.mjs";

const USER_MESSAGES = {
  CONTENT_BLOCKED: "일기 내용 때문에 그림을 만들 수 없었어요. 내용을 조금 바꿔서 다시 시도해 주세요.",
  TIMEOUT: "그림을 만드는 데 시간이 너무 오래 걸렸어요. 잠시 후 다시 시도해 주세요.",
  RATE_LIMITED: "지금 요청이 많아요. 잠시 후 다시 시도해 주세요.",
  DEFAULT: "그림을 만들지 못했어요. 잠시 후 다시 시도해 주세요.",
};

export class JobQueue {
  #jobs = new Map();
  #waiting = [];
  #running = 0;
  #ipHits = new Map();
  #daily = { day: "", count: 0 };

  constructor(assets, log) {
    this.assets = assets;
    this.log = log;
  }

  async init() {
    await mkdir(config.resultDir, { recursive: true });
    // 이전 실행에서 남은 결과 파일은 목록이 사라졌으므로 지운다
    for (const name of await readdir(config.resultDir)) await rm(path.join(config.resultDir, name), { force: true });
    setInterval(() => this.#sweep(), 60_000).unref();
  }

  /** 접수 가능하면 job을, 아니면 { error, status }를 돌려준다. 거절된 요청은 횟수를 차감하지 않는다. */
  submit(diary, ip) {
    if (this.#waiting.length >= config.maxQueue) {
      return { error: "CAPACITY_EXCEEDED", status: 503, message: "지금 만드는 사람이 많아요. 잠시 후 다시 시도해 주세요." };
    }
    const today = new Intl.DateTimeFormat("en-CA", { timeZone: config.dailyLimitZone }).format(new Date());
    if (this.#daily.day !== today) this.#daily = { day: today, count: 0 };
    if (config.dailyLimit && this.#daily.count >= config.dailyLimit) {
      return { error: "DAILY_LIMIT", status: 429, message: "오늘 준비한 엽서가 모두 소진됐어요." };
    }
    const now = Date.now();
    const hits = (this.#ipHits.get(ip) ?? []).filter(t => now - t < 3_600_000);
    if (config.perIpPerHour && hits.length >= config.perIpPerHour) {
      return { error: "RATE_LIMITED", status: 429, message: "한 시간에 만들 수 있는 횟수를 모두 썼어요. 잠시 후 다시 시도해 주세요." };
    }
    hits.push(now);
    this.#ipHits.set(ip, hits);
    this.#daily.count++;

    const job = { id: randomUUID(), status: "queued", stage: null, createdAt: now, diary, title: null, error: null };
    this.#jobs.set(job.id, job);
    this.#waiting.push(job);
    this.log({ event: "job_accepted", jobId: job.id, queue: this.#waiting.length, dailyCount: this.#daily.count });
    this.#pump();
    return { job };
  }

  get(id) {
    const job = this.#jobs.get(id);
    if (!job) return null;
    const position = job.status === "queued" ? this.#waiting.indexOf(job) + 1 : 0;
    return { id: job.id, status: job.status, stage: job.stage, position, title: job.title, error: job.error };
  }

  resultPath(id) {
    const job = this.#jobs.get(id);
    return job?.status === "done" ? path.join(config.resultDir, `${id}.png`) : null;
  }

  stats() {
    return { waiting: this.#waiting.length, running: this.#running, today: this.#daily.count };
  }

  #pump() {
    while (this.#running < config.concurrency && this.#waiting.length) {
      const job = this.#waiting.shift();
      this.#running++;
      this.#run(job).finally(() => { this.#running--; this.#pump(); });
    }
  }

  async #run(job) {
    const started = Date.now();
    job.status = "running";
    const waitedMs = started - job.createdAt;
    try {
      const result = await runPipeline(this.assets, job.diary, stage => { job.stage = stage; });
      await writeFile(path.join(config.resultDir, `${job.id}.png`), result.png);
      job.title = result.title;
      job.status = "done";
      this.log({ event: "job_done", jobId: job.id, waitedMs, tookMs: Date.now() - started, imageAttempts: result.attempts });
    } catch (error) {
      job.status = "failed";
      job.error = { code: error.code ?? "GENERATION_FAILED", message: USER_MESSAGES[error.code] ?? USER_MESSAGES.DEFAULT };
      this.log({ event: "job_failed", jobId: job.id, stage: job.stage, code: error.code, reason: error.message, tookMs: Date.now() - started });
    } finally {
      job.diary = null; // 일기 원문은 생성 후 바로 버린다
      job.finishedAt = Date.now();
    }
  }

  async #sweep() {
    const now = Date.now();
    for (const [id, job] of this.#jobs) {
      if (job.finishedAt && now - job.finishedAt > config.resultTtlMs) {
        this.#jobs.delete(id);
        await rm(path.join(config.resultDir, `${id}.png`), { force: true });
      }
    }
    for (const [ip, hits] of this.#ipHits) if (hits.every(t => now - t >= 3_600_000)) this.#ipHits.delete(ip);
  }
}
