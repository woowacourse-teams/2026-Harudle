import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, mkdir, writeFile, readFile, rm, utimes, symlink } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { createSessionRetention } from "./session-retention.mjs";

const id = "01a13dda-bf94-7672-8cd2-389cf65a99cd";
async function fixture(t) {
  const root = await mkdtemp(path.join(os.tmpdir(), "harudle-session-test-"));
  assert.ok(path.resolve(root).startsWith(path.resolve(os.tmpdir()) + path.sep));
  t.after(() => rm(root, { recursive: true, force: true }));
  const home = path.join(root, "codex-home");
  const sessions = path.join(home, "sessions", "2026", "10", "15");
  await mkdir(sessions, { recursive: true });
  await writeFile(path.join(home, "auth.json"), "keep OAuth credentials");
  const run = path.join(root, "harudle-codex-image-ABC123");
  const retention = createSessionRetention({ codexHome: home, tempDirectory: root, log() {} });
  t.after(() => retention.close());
  async function session(name, cwd = run, ageMs = 0) {
    const file = path.join(sessions, name);
    await writeFile(file, JSON.stringify({ type: "session_meta", payload: { id, cwd } }) + "\nprivate diary\n");
    const time = new Date(Date.now() - ageMs);
    await utimes(file, time, time);
    return file;
  }
  return { root, home, sessions, run, retention, session };
}
async function missing(file) {
  await assert.rejects(readFile(file), { code: "ENOENT" });
}

test("a completed request deletes its session immediately and preserves OAuth and unrelated sessions", async t => {
  const f = await fixture(t);
  const own = await f.session("worker.jsonl");
  const other = await f.session("desktop.jsonl", path.join(f.root, "another-project"));
  const next = await f.session("other-worker.jsonl", path.join(f.root, "harudle-codex-image-DEF456"));
  f.retention.begin(f.run);
  await f.retention.finish(f.run);
  await missing(own);
  assert.match(await readFile(other, "utf8"), /private diary/);
  assert.match(await readFile(next, "utf8"), /private diary/);
  assert.equal(await readFile(path.join(f.home, "auth.json"), "utf8"), "keep OAuth credentials");
});

test("startup cleanup removes old abandoned worker sessions while preserving active and recent sessions", async t => {
  const f = await fixture(t);
  const old = await f.session("old.jsonl", f.run, 2 * 60 * 60 * 1000);
  const activeRun = path.join(f.root, "harudle-codex-image-ACTIVE");
  const active = await f.session("active.jsonl", activeRun, 2 * 60 * 60 * 1000);
  const recent = await f.session("recent.jsonl", path.join(f.root, "harudle-codex-image-RECENT"));
  const unrelated = await f.session("unrelated.jsonl", path.join(f.root, "workspace"), 2 * 60 * 60 * 1000);
  f.retention.begin(activeRun);
  await f.retention.start();
  await missing(old);
  for (const file of [active, recent, unrelated]) assert.match(await readFile(file, "utf8"), /private diary/);
});

test("finishing one concurrent generation preserves the other generation's active session", async t => {
  const f = await fixture(t);
  const otherRun = path.join(f.root, "harudle-codex-image-PARALLEL");
  const first = await f.session("first.jsonl", f.run, 2 * 60 * 60 * 1000);
  const second = await f.session("second.jsonl", otherRun, 2 * 60 * 60 * 1000);
  f.retention.begin(f.run);
  f.retention.begin(otherRun);
  await f.retention.finish(f.run);
  await missing(first);
  assert.match(await readFile(second, "utf8"), /private diary/);
  await f.retention.finish(otherRun);
  await missing(second);
  assert.equal(await readFile(path.join(f.home, "auth.json"), "utf8"), "keep OAuth credentials");
});

test("periodic cleanup retries a leftover session after its retention time", async t => {
  const f = await fixture(t);
  const file = await f.session("leftover.jsonl");
  await f.retention.sweep();
  assert.match(await readFile(file, "utf8"), /private diary/);
  const old = new Date(Date.now() - 2 * 60 * 60 * 1000);
  await utimes(file, old, old);
  await f.retention.sweep();
  await missing(file);
});

test("invalid metadata and sessions outside the worker temp directory are not deleted", async t => {
  const f = await fixture(t);
  const invalid = path.join(f.sessions, "invalid.jsonl");
  await writeFile(invalid, "not JSON\n");
  const external = await f.session("external.jsonl",
    path.join(path.dirname(f.root), "harudle-codex-image-EXTERNAL"), 2 * 60 * 60 * 1000);
  await f.retention.sweep();
  assert.equal(await readFile(invalid, "utf8"), "not JSON\n");
  assert.match(await readFile(external, "utf8"), /private diary/);
});

test("session cleanup does not follow directory links", async t => {
  const f = await fixture(t);
  const outside = path.join(f.root, "outside");
  await mkdir(outside);
  const file = path.join(outside, "keep.jsonl");
  await writeFile(file, JSON.stringify({ type: "session_meta", payload: { id, cwd: f.run } }) + "\n");
  await symlink(outside, path.join(f.home, "sessions", "linked"), process.platform === "win32" ? "junction" : "dir");
  await f.retention.finish(f.run);
  assert.ok((await readFile(file, "utf8")).includes(id));
});

test("cleanup errors are reported without overriding the generation result", async t => {
  const f = await fixture(t);
  const reports = [];
  const retention = createSessionRetention({
    codexHome: f.home, tempDirectory: f.root,
    now() { throw new Error("private details"); }, log: entry => reports.push(JSON.parse(entry)),
  });
  await f.session("worker.jsonl");
  await retention.sweep();
  assert.deepEqual(reports, [{ event: "codex_session_cleanup_failed", code: "cleanup_failed" }]);
});
