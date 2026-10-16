import { Codex } from "@openai/codex-sdk";
import { createHash } from "node:crypto";
import { execFileSync, spawnSync } from "node:child_process";
import { copyFile, mkdir, readFile, readdir, stat, writeFile, appendFile } from "node:fs/promises";
import { createRequire } from "node:module";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const args = process.argv.slice(2);
const option = (name, fallback) => {
  const index = args.indexOf(name);
  if (index < 0) return fallback;
  if (!args[index + 1] || args[index + 1].startsWith("--")) throw new Error("Missing value for " + name);
  return args[index + 1];
};
const timeoutMs = Number(option("--timeout-ms", "300000"));
if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1000) throw new Error("Invalid timeout");
const inspectRun = option("--inspect-run", null);
const serviceInputPath = option("--service-input", null);
const serviceInput = serviceInputPath ? JSON.parse(await readFile(path.resolve(serviceInputPath), "utf8")) : null;
if (!inspectRun && serviceInput?.kind !== "harudle-service-image-input-v1") {
  throw new Error("--service-input must point to input captured from the existing Harudle pipeline");
}
const runId = new Date().toISOString().replace(/[:.]/g, "-");
const runDir = inspectRun ? path.resolve(inspectRun) : path.join(here, "runs", runId);
const previousReport = inspectRun ? JSON.parse(await readFile(path.join(runDir, "report.json"), "utf8")) : null;
const referenceFilename = serviceInput?.referenceFile || previousReport?.referenceFilename || "reference.png";
const reference = serviceInput
  ? path.resolve(path.dirname(path.resolve(serviceInputPath)), referenceFilename)
  : path.join(runDir, referenceFilename);
if (!inspectRun) {
  const sha = value => createHash("sha256").update(value).digest("hex");
  if (sha(await readFile(reference)) !== serviceInput.hashes.reference
      || sha(serviceInput.systemInstruction) !== serviceInput.hashes.imageStyle
      || sha(serviceInput.finalTask) !== serviceInput.hashes.renderedFinalTask) {
    throw new Error("Captured service input hash mismatch");
  }
  await mkdir(runDir, { recursive: true });
  await copyFile(reference, path.join(runDir, referenceFilename));
  await copyFile(path.resolve(serviceInputPath), path.join(runDir, "service-input.json"));
}

function bundledCli() {
  const targets = {
    "win32-x64": ["win32-x64", "x86_64-pc-windows-msvc"],
    "win32-arm64": ["win32-arm64", "aarch64-pc-windows-msvc"],
    "linux-x64": ["linux-x64", "x86_64-unknown-linux-musl"],
    "linux-arm64": ["linux-arm64", "aarch64-unknown-linux-musl"],
    "darwin-x64": ["darwin-x64", "x86_64-apple-darwin"],
    "darwin-arm64": ["darwin-arm64", "aarch64-apple-darwin"],
  };
  const target = targets[process.platform + "-" + process.arch];
  if (!target) throw new Error("Unsupported platform");
  return path.join(path.dirname(require.resolve("@openai/codex-" + target[0] + "/package.json")),
    "vendor", target[1], "bin", process.platform === "win32" ? "codex.exe" : "codex");
}

const executable = path.resolve(option("--codex-path", bundledCli()));
const env = Object.fromEntries(Object.entries(process.env).filter(([key, value]) => {
  if (value === undefined) return false;
  if (key.startsWith("CODEX_") && !["CODEX_HOME", "CODEX_CA_CERTIFICATE"].includes(key)) return false;
  return !["OPENAI_API_KEY", "AZURE_OPENAI_API_KEY", "OPENAI_BASE_URL", "OPENAI_ORG_ID"].includes(key);
}));
const codexHome = env.CODEX_HOME || path.join(os.homedir(), ".codex");
const cliVersion = previousReport?.cliVersion || execFileSync(executable, ["--version"], { env, encoding: "utf8" }).trim();
if (!inspectRun) {
  const auth = spawnSync(executable, ["login", "status"], { env, encoding: "utf8" });
  if (auth.status !== 0 || !(auth.stdout + auth.stderr).includes("ChatGPT")) {
    throw new Error("ChatGPT OAuth login is required; run codex login first");
  }
}
const sdkVersion = JSON.parse((await readFile(path.join(here, "node_modules/@openai/codex-sdk/package.json"), "utf8")).replace(/^\uFEFF/, "")).version;
const imagePromptPath = path.join(runDir, "image-prompt.txt");
const imagePrompt = serviceInput ? [
  serviceInput.systemInstruction,
  serviceInput.referenceInstruction,
  serviceInput.finalTask,
].join("\n\n") : await readFile(imagePromptPath, "utf8").catch(() => "");
const prompt = [
  "Generate exactly ONE new raster image using the built-in image_gen/image_generation tool.",
  "This is a Harudle service-provider validation. The real StoryboardGenerationService has already generated the storyboard.",
  "The existing GeminiDiaryImageGenerator.prepareRequest and DiaryImagePromptRenderer prepared the complete image prompt.",
  "The attached image is the exact reference asset loaded for the active GenerationPrompt, not a sample frontend illustration.",
  "COMPLETE IMAGE PROMPT FILE: " + imagePromptPath,
  "EXACT REFERENCE IMAGE FILE: " + path.join(runDir, referenceFilename),
  process.platform === "win32"
    ? "Read image-prompt.txt with Get-Content -LiteralPath <the complete path above> -Raw -Encoding UTF8. Set max_output_tokens to at least 16000."
    : "Read image-prompt.txt using a read-only exec_command with Node fs.readFileSync to stdout. Set max_output_tokens to at least 16000.",
  "Inside a tools.exec call, pass the read command's output string directly as the native image_gen prompt, without manual rewriting, summarizing, translating, trimming, or adding creative instructions.",
  "Use the exact reference file above as referenced_image_paths. The full prompt already defines the character, style, four scenes, exact Korean captions, footer title, creator handle, and selected colors.",
  "Preserve all of those requirements. Do not invent another storyboard or remove the captions or footer.",
  "Use the native image tool exactly once. Do not use an API key, HTTP image endpoints, external apps, or external MCP tools.",
  "Shell use is limited to reading the prepared prompt. Never create an image using code, SVG, canvas, plotting, or by copying the reference.",
  "Generate a PNG if supported and return its actual absolute file path in imagePath.",
  "If the native tool is unavailable, return status unsupported and the precise reason; do not substitute a different method.",
].join("\n");
if (!inspectRun) {
  await writeFile(imagePromptPath, imagePrompt);
  await writeFile(path.join(runDir, "prompt.txt"), prompt + "\n");
}

const config = {
  forced_login_method: "chatgpt",
  model_provider: "openai",
  features: {
    image_generation: true,
    plugins: false,
    apps: false,
    multi_agent: false,
    browser_use: false,
    computer_use: false,
    shell_tool: true,
  },
};
const rawOverrides = [];
// Disable configured MCP servers without logging configuration values or changing user settings.
try {
  const configText = await readFile(path.join(codexHome, "config.toml"), "utf8");
  const names = new Set();
  for (const match of configText.matchAll(/^\s*\[mcp_servers\.("[^"]+"|[A-Za-z0-9_-]+)(?:\.[^\]]+)?\]\s*$/gm)) {
    names.add(match[1]);
  }
  for (const name of names) rawOverrides.push("mcp_servers." + name + ".enabled=false");
} catch (error) {
  if (error.code !== "ENOENT") throw error;
}
const client = new Codex({ codexPathOverride: executable, env, config, configOverrides: rawOverrides });
const thread = client.startThread({
  workingDirectory: runDir,
  skipGitRepoCheck: true,
  sandboxMode: "workspace-write",
  approvalPolicy: "never",
  webSearchMode: "disabled",
});
const schema = {
  type: "object",
  properties: {
    status: { type: "string", enum: ["generated", "unsupported"] },
    imagePath: { type: ["string", "null"] },
    note: { type: "string" },
  },
  required: ["status", "imagePath", "note"],
  additionalProperties: false,
};
const startedAt = previousReport ? Date.parse(previousReport.startedAt) : Date.now();
const controller = new AbortController();
const timeout = inspectRun ? null : setTimeout(() => controller.abort(), timeoutMs);
const heartbeat = inspectRun ? null : setInterval(() => console.log(JSON.stringify({
  event: "waiting", elapsedSeconds: Math.round((Date.now() - startedAt) / 1000), threadId: thread.id,
})), 15000);
let completed = previousReport?.turnCompleted || false;
let finalResponse = inspectRun ? JSON.parse(await readFile(path.join(runDir, "final-response.json"), "utf8")).raw : null;
let usage = previousReport?.usage || null;
let failure = previousReport?.failure || null;
const eventCounts = previousReport?.eventCounts || {};
const nativeItems = [];
function safeJson(value) {
  return JSON.stringify(value, (key, item) => {
    if (/token|authorization|api.?key/i.test(key) && !/tokens/.test(key)) return "[redacted]";
    if (typeof item === "string" && item.length > 6000) return "[omitted " + item.length + " characters]";
    return item;
  });
}
console.log(JSON.stringify({ event: inspectRun ? "inspect" : "start", sdkVersion, cliVersion, authentication: "chatgpt", runDir }));
if (!inspectRun) try {
  const { events } = await thread.runStreamed([
    { type: "text", text: prompt },
    { type: "local_image", path: path.join(runDir, referenceFilename) },
  ], { outputSchema: schema, signal: controller.signal });
  for await (const event of events) {
    eventCounts[event.type] = (eventCounts[event.type] || 0) + 1;
    await appendFile(path.join(runDir, "events.jsonl"), safeJson(event) + "\n");
    if (event.type === "item.completed" && event.item.type === "agent_message") finalResponse = event.item.text;
    if (event.type === "turn.completed") { completed = true; usage = event.usage; }
    if (event.type === "turn.failed") failure = event.error?.message || "turn.failed";
    if (event.type === "error") failure = event.message;
    if (/image/i.test(event.item?.type || "")) nativeItems.push(event.item);
    console.log(JSON.stringify({ event: event.type, itemType: event.item?.type, status: event.item?.status }));
  }
} catch (error) {
  failure = controller.signal.aborted ? "Timed out after " + timeoutMs + "ms" : error.message;
} finally {
  clearTimeout(timeout);
  clearInterval(heartbeat);
}

let parsed = null;
try { if (finalResponse) parsed = JSON.parse(finalResponse); } catch { /* Retain raw response as evidence. */ }
if (!inspectRun) await writeFile(path.join(runDir, "final-response.json"), safeJson({ raw: finalResponse, parsed }) + "\n");
const imageEvidence = [];
const generatedPaths = new Set();
const nestedImageCalls = new Map();
let lastPreparedPromptRead = null;
function collectEvidence(value) {
  if (!value || typeof value !== "object") return;
  if (["CommandExecution", "command_execution"].includes(value.type) && value.exit_code === 0
      && typeof value.aggregated_output === "string" && imagePrompt
      && JSON.stringify(value.command || "").includes("image-prompt.txt")
      && value.aggregated_output.trimEnd() === imagePrompt.trimEnd()) {
    lastPreparedPromptRead = {
      promptReadItemId: value.id,
      promptSha256: createHash("sha256").update(value.aggregated_output).digest("hex"),
      preparedPromptSha256: createHash("sha256").update(imagePrompt).digest("hex"),
      terminalNewlineOnlyDifference: value.aggregated_output !== imagePrompt,
    };
  }
  if (value.type === "custom_tool_call" && value.name === "exec"
      && /tools\.image_gen__imagegen\s*\(/.test(value.input || "")) {
    const evidence = {
      type: "image_generation", transport: "code_mode.exec", callId: value.call_id, status: "started",
      promptReadFromPreparedFile: /prompt:\s*read\.output/.test(value.input) && value.input.includes("image-prompt.txt"),
    };
    nestedImageCalls.set(value.call_id, evidence);
    imageEvidence.push(evidence);
  }
  if (value.type === "custom_tool_call_output" && nestedImageCalls.has(value.call_id)) {
    const evidence = nestedImageCalls.get(value.call_id);
    if (evidence.promptReadFromPreparedFile && lastPreparedPromptRead) {
      Object.assign(evidence, lastPreparedPromptRead, {
        promptMatchesPrepared: true, promptProofSource: "command_execution.aggregated_output",
      });
    }
    let blocks = value.output;
    if (typeof blocks === "string") { try { blocks = JSON.parse(blocks); } catch { blocks = []; } }
    for (const block of Array.isArray(blocks) ? blocks : []) {
      if (block.type === "input_text" && typeof block.text === "string" && imagePrompt
          && block.text.trimEnd() === imagePrompt.trimEnd()) {
        evidence.promptMatchesPrepared = true;
        evidence.promptSha256 = createHash("sha256").update(block.text).digest("hex");
        evidence.preparedPromptSha256 = createHash("sha256").update(imagePrompt).digest("hex");
        evidence.terminalNewlineOnlyDifference = block.text !== imagePrompt;
      }
      if (block.type === "input_image" && block.image_url?.startsWith("data:image/png;base64,")) {
        const bytes = Buffer.from(block.image_url.slice("data:image/png;base64,".length), "base64");
        evidence.status = "completed";
        evidence.returnedImageBytes = bytes.length;
        evidence.sha256 = createHash("sha256").update(bytes).digest("hex");
      }
    }
  }
  const label = [value.type, value.name, value.tool_name, value.recipient_name].filter(Boolean).join(" ");
  if (/image_gen|imagegen|image_generation/i.test(label)) {
    const evidence = {
      type: value.type, name: value.name, status: value.status,
      keys: Object.keys(value),
    };
    for (const key of ["saved_path", "savedPath", "output_path", "outputPath", "image_path", "imagePath"]) {
      if (typeof value[key] === "string") { evidence[key] = value[key]; generatedPaths.add(value[key]); }
    }
    if (typeof value.result === "string") {
      evidence.resultCharacters = value.result.length;
      if (value.type === "image_generation_call" && value.status === "completed" && value.result.length > 100) {
        const bytes = Buffer.from(value.result, "base64");
        evidence.sha256 = createHash("sha256").update(bytes).digest("hex");
        evidence.returnedImageBytes = bytes.length;
      }
    }
    imageEvidence.push(evidence);
  }
  for (const [key, child] of Object.entries(value)) {
    if (key === "result" && typeof child === "string" && child.length > 6000) continue;
    if (child && typeof child === "object") collectEvidence(child);
  }
}
for (const item of nativeItems) collectEvidence(item);
const threadId = previousReport?.threadId || thread.id;
if (threadId) {
  const sessions = path.join(codexHome, "sessions");
  try {
    const entries = await readdir(sessions, { recursive: true, withFileTypes: true });
    const entry = entries.find(item => item.isFile() && item.name.endsWith(threadId + ".jsonl"));
    if (entry) {
      const text = await readFile(path.join(entry.parentPath, entry.name), "utf8");
      for (const line of text.split("\n")) {
        if (!line) continue;
        try { collectEvidence(JSON.parse(line).payload); } catch { /* Ignore incomplete final line. */ }
      }
    }
  } catch (error) {
    imageEvidence.push({ diagnostic: "Could not read this thread's image events", code: error.code });
  }
}
if (parsed?.imagePath) generatedPaths.add(parsed.imagePath);
let image = null;
for (const candidate of generatedPaths) {
  try {
    const resolved = path.resolve(runDir, candidate);
    if (resolved === path.join(runDir, referenceFilename) || resolved === reference) continue;
    const info = await stat(resolved);
    if (info.mtimeMs < startedAt - 2000) continue;
    const bytes = await readFile(resolved);
    const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
    if (bytes.length < 33 || !bytes.subarray(0, 8).equals(signature)) continue;
    const original = await readFile(path.join(runDir, referenceFilename));
    const hash = createHash("sha256").update(bytes).digest("hex");
    if (hash === createHash("sha256").update(original).digest("hex")) continue;
    const destination = path.join(runDir, "generated.png");
    if (resolved !== destination) await copyFile(resolved, destination);
    image = { path: destination, bytes: bytes.length, width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20), sha256: hash };
    break;
  } catch { /* Try another tool-reported candidate. */ }
}
const nativeGenerationObserved = imageEvidence.some(item =>
  item.status === "completed" && item.sha256 && item.sha256 === image?.sha256);
const inputMode = serviceInput ? "existing-service-pipeline" : previousReport?.inputMode || "smoke-test";
const servicePromptObserved = imageEvidence.some(item => item.status === "completed"
  && item.sha256 === image?.sha256 && item.promptReadFromPreparedFile && item.promptMatchesPrepared);
const success = completed && !failure && parsed?.status === "generated" && image !== null && nativeGenerationObserved
  && (inputMode !== "existing-service-pipeline" || servicePromptObserved);
const report = {
  success, sdkVersion, cliVersion, authentication: "chatgpt", apiKeyProvided: false,
  startedAt: new Date(startedAt).toISOString(), elapsedMs: previousReport?.elapsedMs || Date.now() - startedAt,
  threadId, referenceFilename, turnCompleted: completed, failure,
  inputMode, servicePromptObserved,
  serviceInput: serviceInput ? {
    environment: serviceInput.environment, promptId: serviceInput.promptId,
    providerInfo: serviceInput.providerInfo, storyboardElapsedMs: serviceInput.storyboardElapsedMs,
    title: serviceInput.storyboard.title, captions: serviceInput.storyboard.panels.map(panel => panel.caption),
    lineage: serviceInput.lineage, hashes: serviceInput.hashes, deployedJarSha256: serviceInput.deployedJarSha256,
  } : previousReport?.serviceInput || null,
  finalStatus: parsed?.status, eventCounts, usage, nativeGenerationObserved, imageEvidence, image,
};
await writeFile(path.join(runDir, "report.json"), JSON.stringify(report, null, 2) + "\n");
console.log(JSON.stringify(report, null, 2));
process.exitCode = success ? 0 : 1;
