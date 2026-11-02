import { createReadStream } from "node:fs";
import { lstat, readdir, unlink } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

const RETENTION_MS = 60 * 60 * 1000;
const INTERVAL_MS = 5 * 60 * 1000;
const MAX_METADATA_BYTES = 256 * 1024;
const threadIdPattern = /^[a-f0-9-]{36}$/;
const normalize = value => {
  const absolute = path.resolve(value);
  return process.platform === "win32" ? absolute.toLowerCase() : absolute;
};

async function* sessionFiles(directory) {
  let info;
  try { info = await lstat(directory); } catch (error) {
    if (error.code === "ENOENT") return;
    throw error;
  }
  if (!info.isDirectory() || info.isSymbolicLink()) return;
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const file = path.join(directory, entry.name);
    if (entry.isDirectory()) yield* sessionFiles(file);
    else if (entry.isFile() && entry.name.endsWith(".jsonl")) yield file;
  }
}

async function sessionMetadata(file) {
  let firstLine = "";
  const stream = createReadStream(file, { encoding: "utf8", highWaterMark: 16384 });
  try {
    for await (const chunk of stream) {
      firstLine += chunk;
      const newline = firstLine.indexOf("\n");
      if (newline >= 0) { firstLine = firstLine.slice(0, newline); break; }
      if (Buffer.byteLength(firstLine) > MAX_METADATA_BYTES) return null;
    }
    if (Buffer.byteLength(firstLine) > MAX_METADATA_BYTES) return null;
    const record = JSON.parse(firstLine);
    return record.type === "session_meta" && threadIdPattern.test(record.payload?.id || "")
      ? record.payload : null;
  } catch (error) {
    if (error instanceof SyntaxError || error.code === "ENOENT") return null;
    throw error;
  } finally {
    stream.destroy();
  }
}

/** Delete only this worker's session JSONL files; never traverse auth files or symlinks. */
export function createSessionRetention({
  codexHome, log = console.log, now = Date.now, retentionMs = RETENTION_MS, intervalMs = INTERVAL_MS,
  tempDirectory = os.tmpdir(),
}) {
  const sessionsRoot = path.resolve(codexHome, "sessions");
  const activeRuns = new Set();
  let pending = Promise.resolve();
  let timer;
  const report = entry => {
    try { log(JSON.stringify(entry)); } catch { /* Diagnostics must not replace the generation result. */ }
  };
  const ownedRun = cwd => typeof cwd === "string" && path.isAbsolute(cwd)
    && normalize(path.dirname(cwd)) === normalize(tempDirectory)
    && /^harudle-codex-image-[A-Za-z0-9]+$/.test(path.basename(cwd));

  async function cleanup(finishedRun) {
    let removed = 0;
    for await (const file of sessionFiles(sessionsRoot)) {
      const relative = path.relative(sessionsRoot, path.resolve(file));
      if (relative.startsWith("..") || path.isAbsolute(relative)) continue;
      let info;
      try { info = await lstat(file); } catch (error) {
        if (error.code === "ENOENT") continue;
        throw error;
      }
      if (!info.isFile() || info.isSymbolicLink()) continue;
      const metadata = await sessionMetadata(file);
      if (!metadata || !ownedRun(metadata.cwd) || activeRuns.has(normalize(metadata.cwd))) continue;
      const finished = finishedRun && normalize(metadata.cwd) === normalize(finishedRun);
      if (!finished && info.mtimeMs > now() - retentionMs) continue;
      try { await unlink(file); removed++; } catch (error) {
        if (error.code !== "ENOENT") throw error;
      }
    }
    if (removed) report({ event: "codex_sessions_removed", count: removed });
  }

  function enqueue(finishedRun) {
    pending = pending.then(() => cleanup(finishedRun)).catch(error => {
      report({ event: "codex_session_cleanup_failed", code: error.code || "cleanup_failed" });
    });
    return pending;
  }

  return {
    async start() {
      await enqueue();
      timer = setInterval(() => { void enqueue(); }, intervalMs);
      timer.unref();
    },
    begin(runDir) { activeRuns.add(normalize(runDir)); },
    finish(runDir) {
      activeRuns.delete(normalize(runDir));
      return enqueue(runDir);
    },
    sweep() { return enqueue(); },
    close() { clearInterval(timer); return pending; },
  };
}
