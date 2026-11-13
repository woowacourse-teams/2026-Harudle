import assert from "node:assert/strict";
import { test } from "node:test";

// config는 import 시점에 환경 변수를 읽으므로 먼저 설정한다
process.env.EVENT_PRINT_QUEUE = "true";
process.env.EVENT_PRINT_KEY = "test-print-key-1234";
process.env.EVENT_PRINT_LEASE_SECONDS = "120";
process.env.EVENT_CONCURRENCY = "1";
const { JobQueue } = await import("../src/jobs.mjs");

function setup() {
  let clock = 1_700_000_000_000;
  const queue = new JobQueue({}, () => {}, {
    now: () => clock,
    runner: async () => ({ title: "제목", png: Buffer.from("png"), attempts: 1 }),
    writeResult: async () => {},
  });
  return { queue, advance: ms => { clock += ms; } };
}
const settle = () => new Promise(r => setTimeout(r, 10));

test("접수 순서대로 번호표가 붙고, 완성되면 인쇄 대기열에 들어간다", async () => {
  const { queue } = setup();
  const a = queue.submit("첫 번째 이야기입니다", "ip").job;
  const b = queue.submit("두 번째 이야기입니다", "ip").job;
  assert.equal(a.ticket, 1);
  assert.equal(b.ticket, 2);
  await settle();
  assert.deepEqual(queue.printStatus().queued.map(j => j.ticket), [1, 2]);
  assert.equal(queue.get(a.id).print, "queued");
});

test("크루 닉네임이 번호표와 함께 조회·인쇄 현황에 나온다", async () => {
  const { queue } = setup();
  const job = queue.submit("닉네임 시험용 이야기입니다", "ip", "제신").job;
  assert.equal(queue.get(job.id).nickname, "제신");
  await settle();
  assert.deepEqual(queue.printStatus().queued.map(j => [j.ticket, j.nickname]), [[1, "제신"]]);
  assert.equal(queue.claimPrint(job.id).nickname, "제신");
});

test("같은 엽서를 두 번 가져가지 않고, 완료 후 최근 목록에 남는다", async () => {
  const { queue } = setup();
  queue.submit("첫 번째 이야기입니다", "ip");
  queue.submit("두 번째 이야기입니다", "ip");
  await settle();
  const first = queue.claimPrint();
  const second = queue.claimPrint();
  assert.equal(first.ticket, 1);
  assert.equal(second.ticket, 2);
  assert.equal(queue.claimPrint(), null);
  assert.equal(queue.markPrinted(first.id), true);
  assert.equal(queue.markPrinted(first.id), false);
  assert.deepEqual(queue.printStatus().recent.map(j => j.ticket), [1]);
});

test("잠금 시간 안에 완료 보고가 없으면 다시 가져갈 수 있다", async () => {
  const { queue, advance } = setup();
  queue.submit("첫 번째 이야기입니다", "ip");
  await settle();
  const claimed = queue.claimPrint();
  assert.equal(queue.claimPrint(), null);
  advance(121_000);
  assert.equal(queue.claimPrint().id, claimed.id);
});

test("수동 확인: 고른 엽서를 순서와 상관없이 인쇄하고, 인쇄 안 함은 되살릴 수 있다", async () => {
  const { queue } = setup();
  const a = queue.submit("첫 번째 이야기입니다", "ip").job;
  const b = queue.submit("두 번째 이야기입니다", "ip").job;
  await settle();
  assert.equal(queue.claimPrint(b.id).ticket, 2);
  assert.equal(queue.claimPrint(b.id), null);
  assert.equal(queue.skipPrint(a.id), true);
  assert.deepEqual(queue.printStatus().queued, []);
  assert.equal(queue.printStatus().recent[0].skipped, true);
  assert.equal(queue.reprint(a.id), true);
  assert.deepEqual(queue.printStatus().queued.map(j => j.ticket), [1]);
});

test("다시 뽑기는 대기열 맨 앞에 넣는다", async () => {
  const { queue } = setup();
  queue.submit("첫 번째 이야기입니다", "ip");
  queue.submit("두 번째 이야기입니다", "ip");
  await settle();
  const first = queue.claimPrint();
  queue.markPrinted(first.id);
  assert.equal(queue.reprint(first.id), true);
  assert.deepEqual(queue.printStatus().queued.map(j => j.ticket), [1, 2]);
  assert.equal(queue.claimPrint().ticket, 1);
});
