import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "../../..");
if (!process.argv[2]) throw new Error("Usage: node verify-renderer.mjs /path/to/service-input.json");
const inputPath = path.resolve(process.argv[2]);
const snapshot = path.dirname(inputPath);
const input = JSON.parse(await fs.readFile(inputPath, "utf8"));
const q = JSON.stringify;
const list = values => "java.util.List.of(" + values.map(q).join(",") + ")";
const panels = input.storyboard.panels.map(p => "new StoryPanel(" + [
  p.panelNumber, q(p.caption), q(p.scene), q(p.characters), q(p.emotion), list(p.props),
].join(",") + ")");
const colors = input.storyboard.scenePlan.focalColors.map(c => "new FocalColor(" + [
  q(c.prop), q(c.component), q(c.colorHex), "java.util.List.of(" + c.panelNumbers.join(",") + ")",
].join(",") + ")");
const source = [
  "import com.harudle.generation.diary.domain.*;",
  "import com.harudle.generation.adapter.out.gemini.DiaryImagePromptRenderer;",
  "import java.nio.file.*;",
  "public class RenderCapturedStoryboard {",
  " public static void main(String[] args) throws Exception {",
  " var storyboard = new Storyboard(" + q(input.storyboard.title) + "," + q(input.storyboard.castContinuity)
    + ",java.util.List.of(" + panels.join(",") + "),new ScenePlan("
    + q(input.storyboard.scenePlan.environmentRule) + ",java.util.List.of(" + colors.join(",") + ")));",
  ' Files.writeString(Path.of(args[0]), "[Final Task]\\n" + new DiaryImagePromptRenderer().render(storyboard), java.nio.charset.StandardCharsets.UTF_8);',
  " }",
  "}",
].join("\n");
const sourceFile = path.join(snapshot, "RenderCapturedStoryboard.java");
const classes = path.join(snapshot, "renderer-classes");
await fs.mkdir(classes, { recursive: true });
await fs.writeFile(sourceFile, source);
const sources = [
  "common/validation/TextValidator.java",
  "generation/diary/domain/FocalColor.java",
  "generation/diary/domain/ScenePlan.java",
  "generation/diary/domain/StoryPanel.java",
  "generation/diary/domain/Storyboard.java",
  "generation/adapter/out/gemini/DiaryImagePromptRenderer.java",
].map(file => path.join(root, "backend/src/main/java/com/harudle", file));
execFileSync("javac", ["-encoding", "UTF-8", "-d", classes, ...sources, sourceFile], { stdio: "inherit" });
const output = path.join(snapshot, "repository-final-task.txt");
execFileSync("java", ["-cp", classes, "RenderCapturedStoryboard", output], { stdio: "inherit" });
const hash = createHash("sha256").update(await fs.readFile(output)).digest("hex");
const result = {
  equal: hash === input.hashes.renderedFinalTask,
  repositoryRendererSha256: hash,
  capturedProviderFinalTaskSha256: input.hashes.renderedFinalTask,
};
await fs.writeFile(path.join(snapshot, "renderer-comparison.json"), JSON.stringify(result, null, 2) + "\n");
console.log(JSON.stringify(result, null, 2));
process.exitCode = result.equal ? 0 : 1;
