import { createHash } from "node:crypto";
import { Codex } from "@openai/codex-sdk";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdtemp, readFile, realpath, rm, stat, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import os from "node:os";
import path from "node:path";
import { MAX_BYTES, WorkerError, validatePng } from "./image.mjs";

const require = createRequire(import.meta.url);
const targets = {
  "win32-x64": ["win32-x64", "x86_64-pc-windows-msvc"],
  "win32-arm64": ["win32-arm64", "aarch64-pc-windows-msvc"],
  "linux-x64": ["linux-x64", "x86_64-unknown-linux-musl"],
  "linux-arm64": ["linux-arm64", "aarch64-unknown-linux-musl"],
  "darwin-x64": ["darwin-x64", "x86_64-apple-darwin"],
  "darwin-arm64": ["darwin-arm64", "aarch64-apple-darwin"],
};

export function bundledCli() {
  const target = targets[process.platform + "-" + process.arch];
  if (!target) throw new Error("Unsupported worker platform");
  return path.join(path.dirname(require.resolve("@openai/codex-" + target[0] + "/package.json")),
    "vendor", target[1], "bin", process.platform === "win32" ? "codex.exe" : "codex");
}

export function sdkEnvironment(source = process.env) {
  // Do not forward the internal worker token, backend credentials, API keys, or app-injected CODEX_* state.
  const allowed = new Set([
    "PATH", "PATHEXT", "HOME", "USERPROFILE", "SYSTEMROOT", "WINDIR", "COMSPEC", "TEMP", "TMP", "TMPDIR",
    "LANG", "LC_ALL", "TERM", "HTTPS_PROXY", "HTTP_PROXY", "ALL_PROXY", "NO_PROXY",
    "SSL_CERT_FILE", "SSL_CERT_DIR", "CODEX_HOME", "CODEX_CA_CERTIFICATE",
  ]);
  return Object.fromEntries(Object.entries(source).filter(([key, value]) =>
    value !== undefined && allowed.has(key.toUpperCase())));
}

const outputSchema = {
  type: "object",
  properties: {
    status: { type: "string", enum: ["generated", "unsupported"] },
    imagePath: { type: ["string", "null"] },
  },
  required: ["status", "imagePath"], additionalProperties: false,
};

export async function createCodexGenerator({ sourceEnv = process.env, log = console.log } = {}) {
  const env = sdkEnvironment(sourceEnv);
  const codexHome = path.resolve(env.CODEX_HOME || path.join(os.homedir(), ".codex"));
  env.CODEX_HOME = codexHome;
  const executable = bundledCli();
  const auth = spawnSync(executable, ["login", "status"], { env, encoding: "utf8", timeout: 15000 });
  if (auth.status !== 0 || !(auth.stdout + auth.stderr).includes("ChatGPT")) {
    throw new Error("Worker requires ChatGPT OAuth login in its dedicated CODEX_HOME");
  }
  const version = execFileSync(executable, ["--version"], { env, encoding: "utf8", timeout: 15000 }).trim();
  const configOverrides = [];
  try {
    const configText = await readFile(path.join(codexHome, "config.toml"), "utf8");
    for (const name of new Set([...configText.matchAll(
      /^\s*\[mcp_servers\.("[^"]+"|[A-Za-z0-9_-]+)(?:\.[^\]]+)?\]\s*$/gm
    )].map(match => match[1]))) configOverrides.push("mcp_servers." + name + ".enabled=false");
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  const codex = new Codex({
    codexPathOverride: executable, env, configOverrides,
    config: {
      forced_login_method: "chatgpt", model_provider: "openai",
      features: {
        image_generation: true, plugins: false, apps: false, multi_agent: false,
        browser_use: false, computer_use: false, shell_tool: true,
      },
    },
  });
  log(JSON.stringify({ event: "codex_ready", version, authentication: "chatgpt" }));

  return async function generate({ prompt, reference, extension }, signal) {
    signal.throwIfAborted();
    const runDir = await mkdtemp(path.join(os.tmpdir(), "harudle-codex-image-"));
    let thread;
    try {
      const started = Date.now();
      const promptPath = path.join(runDir, "image-prompt.txt");
      const referencePath = path.join(runDir, "reference." + extension);
      await writeFile(promptPath, prompt, { mode: 0o600 });
      await writeFile(referencePath, reference, { mode: 0o600 });
      const control = [
        "Generate exactly ONE new raster PNG using the built-in image_gen/image_generation tool.",
        "The Harudle StoryboardGenerationService already created the storyboard. Do not create or rewrite it.",
        "COMPLETE PREPARED IMAGE PROMPT: " + promptPath,
        "EXACT REFERENCE IMAGE: " + referencePath,
        process.platform === "win32"
          ? "Read image-prompt.txt with Get-Content -LiteralPath <the complete path above> -Raw -Encoding UTF8."
          : "Read image-prompt.txt using a read-only exec_command with Node fs.readFileSync to stdout.",
        "Set max_output_tokens to at least 16000 so the complete prepared prompt is returned.",
        "Inside a tools.exec call, pass the read command output string directly as the native image_gen prompt.",
        "Do not rewrite, summarize, translate, trim, or add instructions to that string.",
        "Use the exact reference file as referenced_image_paths. Preserve all scenes, captions, footer and colors.",
        "Call the native image tool exactly once. Shell use is limited to reading the prepared prompt.",
        "Never use API keys, HTTP image endpoints, external apps/MCP tools, SVG, canvas, plotting, or copying the reference.",
        "Return status generated and the native image tool's actual absolute PNG imagePath.",
        "If the native tool is unavailable, return status unsupported and imagePath null.",
      ].join("\n");
      thread = codex.startThread({
        workingDirectory: runDir, skipGitRepoCheck: true, sandboxMode: "workspace-write",
        approvalPolicy: "never", webSearchMode: "disabled",
      });
      const result = await thread.run([
        { type: "text", text: control }, { type: "local_image", path: referencePath },
      ], { outputSchema, signal });
      signal.throwIfAborted();
      let output;
      try { output = JSON.parse(result.finalResponse); } catch { throw new WorkerError(502, "invalid_codex_response"); }
      if (output.status !== "generated" || typeof output.imagePath !== "string") {
        throw new WorkerError(502, "native_image_generation_unavailable");
      }
      // Never trust an agent-returned path outside this thread's native image artifacts.
      if (!/^[a-f0-9-]{36}$/.test(thread.id || "")) throw new WorkerError(502, "invalid_thread_id");
      const allowedRoot = await realpath(path.join(codexHome, "generated_images", thread.id));
      const candidate = await realpath(output.imagePath);
      const relative = path.relative(allowedRoot, candidate);
      if (!relative || relative.startsWith("..") || path.isAbsolute(relative)) {
        throw new WorkerError(502, "invalid_image_path");
      }
      const info = await stat(candidate);
      if (!info.isFile() || info.size > MAX_BYTES || info.mtimeMs < started - 2000) {
        throw new WorkerError(502, "invalid_generated_image");
      }
      const image = validatePng(await readFile(candidate), reference);
      const sha = value => createHash("sha256").update(value).digest("hex");
      log(JSON.stringify({ event: "codex_image_generated", threadId: thread.id,
        elapsedMs: Date.now() - started, imageBytes: image.length, imageSha256: sha(image),
        promptSha256: sha(prompt), referenceSha256: sha(reference) }));
      return image;
    } finally {
      await rm(runDir, { recursive: true, force: true });
      if (/^[a-f0-9-]{36}$/.test(thread?.id || "")) {
        await rm(path.join(codexHome, "generated_images", thread.id), { recursive: true, force: true });
      }
    }
  };
}
