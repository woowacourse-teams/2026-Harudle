import { createHash, randomBytes } from "node:crypto";
import { once } from "node:events";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createWorkerServer } from "../../codex-image-worker/server.mjs";
import { createCodexGenerator } from "../../codex-image-worker/generator.mjs";

if (!process.argv[2]) throw new Error("Usage: node verify-worker.mjs <captured-service-input.json>");
const inputPath = path.resolve(process.argv[2]);
const input = JSON.parse(await readFile(inputPath, "utf8"));
if (input.kind !== "harudle-service-image-input-v1") throw new Error("Actual captured service input is required");
const sha = value => createHash("sha256").update(value).digest("hex");
const reference = await readFile(path.join(path.dirname(inputPath), input.referenceFile));
if (sha(reference) !== input.hashes.reference || sha(input.systemInstruction) !== input.hashes.imageStyle
    || sha(input.finalTask) !== input.hashes.renderedFinalTask) throw new Error("Captured input hash mismatch");
const here = path.dirname(fileURLToPath(import.meta.url));
const runDir = path.join(here, "runs", "worker-" + new Date().toISOString().replace(/[:.]/g, "-"));
await mkdir(runDir, { recursive: true });
const events = [];
const generate = await createCodexGenerator({ log: entry => {
  events.push(JSON.parse(entry)); console.log(entry);
} });
const token = randomBytes(32).toString("hex");
const server = createWorkerServer({ token, generate, timeoutMs: 360000 });
server.listen(0, "127.0.0.1");
await once(server, "listening");
const heartbeat = setInterval(() => console.log(JSON.stringify({ event: "waiting_for_http_worker" })), 15000);
const started = Date.now();
try {
  const response = await fetch("http://127.0.0.1:" + server.address().port + "/v1/images/generations", {
    method: "POST", headers: { Authorization: "Bearer " + token, "Content-Type": "application/json" },
    body: JSON.stringify({
      systemInstruction: input.systemInstruction, referenceInstruction: input.referenceInstruction,
      finalTask: input.finalTask, reference: { mediaType: input.referenceMediaType, base64: reference.toString("base64") },
    }),
    signal: AbortSignal.timeout(370000),
  });
  if (!response.ok) throw new Error("Worker HTTP " + response.status + ": " + await response.text());
  const image = Buffer.from(await response.arrayBuffer());
  const destination = path.join(runDir, "generated.png");
  await writeFile(destination, image);
  const evidence = events.find(event => event.event === "codex_image_generated");
  const report = {
    success: evidence?.imageSha256 === sha(image), transport: "production-http-worker",
    source: inputPath, title: input.storyboard.title, elapsedMs: Date.now() - started,
    hashes: input.hashes, evidence, image: destination, imageSha256: sha(image), imageBytes: image.length,
  };
  await writeFile(path.join(runDir, "report.json"), JSON.stringify(report, null, 2) + "\n");
  console.log(JSON.stringify(report, null, 2));
  if (!report.success) process.exitCode = 1;
} finally {
  clearInterval(heartbeat);
  server.abortActive();
  await generate.close?.();
  server.closeAllConnections();
  server.close();
}
