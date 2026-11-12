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
  // 인쇄 대기열: 완성 순서대로 job id. 인쇄 화면이 하나씩 가져가 일정 시간 잠근다(lease).
  #printQueue = [];

  constructor(assets, log, { now = () => Date.now(), runner = runPipeline, writeResult } = {}) {
    this.assets = assets;
    this.log = log;
    this.now = now;
    this.runner = runner;
    this.writeResult = writeResult ?? ((id, png) => writeFile(path.join(config.resultDir, `${id}.png`), png));
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
    const now = this.now();
    const hits = (this.#ipHits.get(ip) ?? []).filter(t => now - t < 3_600_000);
    if (config.perIpPerHour && hits.length >= config.perIpPerHour) {
      return { error: "RATE_LIMITED", status: 429, message: "한 시간에 만들 수 있는 횟수를 모두 썼어요. 잠시 후 다시 시도해 주세요." };
    }
    hits.push(now);
    this.#ipHits.set(ip, hits);
    this.#daily.count++;

    // 그날의 접수 순번을 번호표로 쓴다. 프린터 앞에서 자기 엽서를 찾는 데 쓴다.
    const job = { id: randomUUID(), ticket: this.#daily.count, status: "queued", stage: null, createdAt: now, diary, title: null, error: null, print: null };
    this.#jobs.set(job.id, job);
    this.#waiting.push(job);
    this.log({ event: "job_accepted", jobId: job.id, ticket: job.ticket, queue: this.#waiting.length, dailyCount: this.#daily.count });
    this.#pump();
    return { job };
  }

  get(id) {
    const job = this.#jobs.get(id);
    if (!job) return null;
    const position = job.status === "queued" ? this.#waiting.indexOf(job) + 1 : 0;
    return { id: job.id, ticket: job.ticket, status: job.status, stage: job.stage, position, title: job.title, error: job.error, print: job.print?.state ?? null };
  }

  #releaseExpiredLeases(now) {
    for (const job of this.#jobs.values()) {
      if (job.print?.state === "printing" && job.print.leaseUntil < now) {
        job.print = { state: "queued" };
        this.#printQueue.unshift(job.id);
        this.log({ event: "print_lease_expired", jobId: job.id, ticket: job.ticket });
      }
    }
  }

  /**
   * 인쇄할 엽서를 하나 잠가서 돌려준다. id를 주면 그 엽서를(수동 확인 후 인쇄), 없으면 대기열 맨 앞을 가져간다.
   * 가져갈 엽서가 없으면 null. 잠금이 만료된 엽서는 대기열 앞으로 돌려놓는다.
   */
  claimPrint(id) {
    const now = this.now();
    this.#releaseExpiredLeases(now);
    const index = id ? this.#printQueue.indexOf(id) : 0;
    if (index < 0) return null;
    while (index < this.#printQueue.length) {
      const [jobId] = this.#printQueue.splice(index, 1);
      const job = this.#jobs.get(jobId);
      if (!job || job.status !== "done" || job.print?.state !== "queued") { if (id) return null; continue; }
      job.print = { state: "printing", leaseUntil: now + config.printLeaseMs, claimedAt: now };
      this.log({ event: "print_claimed", jobId: job.id, ticket: job.ticket });
      return { id: job.id, ticket: job.ticket, title: job.title };
    }
    return null;
  }

  /** 인쇄 안 함: 확인해 보고 뽑지 않기로 한 엽서를 대기열에서 뺀다. 다시 뽑기로 되살릴 수 있다. */
  skipPrint(id) {
    const job = this.#jobs.get(id);
    if (!job || job.print?.state !== "queued") return false;
    this.#printQueue = this.#printQueue.filter(x => x !== id);
    job.print = { state: "skipped", printedAt: this.now() };
    this.log({ event: "print_skipped", jobId: job.id, ticket: job.ticket });
    return true;
  }

  /** 인쇄 완료. 잠금을 가진 엽서만 완료로 바꾼다. */
  markPrinted(id) {
    const job = this.#jobs.get(id);
    if (!job || job.print?.state !== "printing") return false;
    job.print = { state: "printed", printedAt: this.now() };
    this.log({ event: "print_done", jobId: job.id, ticket: job.ticket });
    return true;
  }

  /** 다시 뽑기: 완성된 엽서를 대기열 맨 앞에 넣는다. */
  reprint(id) {
    const job = this.#jobs.get(id);
    if (!job || job.status !== "done") return false;
    this.#printQueue = this.#printQueue.filter(x => x !== id);
    job.print = { state: "queued" };
    this.#printQueue.unshift(id);
    this.log({ event: "print_requeued", jobId: job.id, ticket: job.ticket });
    return true;
  }

  /** 인쇄 화면용 현황: 대기 중 번호, 인쇄 중, 최근 인쇄·건너뜀 10개 */
  printStatus() {
    this.#releaseExpiredLeases(this.now());
    const all = [...this.#jobs.values()];
    const brief = j => ({ id: j.id, ticket: j.ticket, title: j.title });
    return {
      queued: this.#printQueue.map(id => this.#jobs.get(id)).filter(j => j?.print?.state === "queued").map(brief),
      printing: all.filter(j => j.print?.state === "printing").map(brief),
      recent: all.filter(j => j.print?.state === "printed" || j.print?.state === "skipped")
        .sort((a, b) => b.print.printedAt - a.print.printedAt).slice(0, 10)
        .map(j => ({ ...brief(j), skipped: j.print.state === "skipped" })),
      generating: all.filter(j => j.status === "queued" || j.status === "running").map(j => ({ ticket: j.ticket })),
    };
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
    const started = this.now();
    job.status = "running";
    const waitedMs = started - job.createdAt;
    try {
      const result = await this.runner(this.assets, job.diary, stage => { job.stage = stage; });
      await this.writeResult(job.id, result.png);
      job.title = result.title;
      job.status = "done";
      if (config.printQueue) {
        job.print = { state: "queued" };
        this.#printQueue.push(job.id);
      }
      this.log({ event: "job_done", jobId: job.id, ticket: job.ticket, waitedMs, tookMs: this.now() - started, imageAttempts: result.attempts });
    } catch (error) {
      job.status = "failed";
      job.error = { code: error.code ?? "GENERATION_FAILED", message: USER_MESSAGES[error.code] ?? USER_MESSAGES.DEFAULT };
      this.log({ event: "job_failed", jobId: job.id, ticket: job.ticket, stage: job.stage, code: error.code, reason: error.message, tookMs: this.now() - started });
    } finally {
      job.diary = null; // 일기 원문은 생성 후 바로 버린다
      job.finishedAt = this.now();
    }
  }

  async #sweep() {
    const now = this.now();
    for (const [id, job] of this.#jobs) {
      if (job.finishedAt && now - job.finishedAt > config.resultTtlMs) {
        this.#jobs.delete(id);
        this.#printQueue = this.#printQueue.filter(x => x !== id);
        await rm(path.join(config.resultDir, `${id}.png`), { force: true });
      }
    }
    for (const [ip, hits] of this.#ipHits) if (hits.every(t => now - t >= 3_600_000)) this.#ipHits.delete(ip);
  }
}
