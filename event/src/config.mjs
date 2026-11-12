import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const env = process.env;

const int = (name, fallback) => {
  const value = env[name];
  if (value === undefined || value === "") return fallback;
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 0) throw new Error(`${name}은 0 이상의 정수여야 합니다: ${value}`);
  return parsed;
};

export const config = Object.freeze({
  port: int("EVENT_PORT", 3100),
  host: env.EVENT_HOST ?? "127.0.0.1",
  assetsDir: path.resolve(env.EVENT_ASSETS_DIR ?? path.join(root, "assets")),
  resultDir: path.resolve(env.EVENT_RESULT_DIR ?? path.join(root, "data", "results")),
  publicDir: path.join(root, "public"),

  fakeAi: env.EVENT_FAKE_AI === "true",
  project: env.GOOGLE_CLOUD_PROJECT ?? "",
  location: env.GOOGLE_CLOUD_LOCATION ?? "global",
  storyboardModel: env.EVENT_STORYBOARD_MODEL ?? "gemini-3.5-flash",
  storyboardThinkingLevel: (env.EVENT_STORYBOARD_THINKING_LEVEL ?? "medium").toUpperCase(),
  // 4x6 300dpi 출력을 위해 2K로 바로 받는다. gemini-nano-banana-2.1은 imageSize를 무시하고 848x1264만 돌려준다.
  imageModel: env.EVENT_IMAGE_MODEL ?? "gemini-3.1-flash-image",
  imageSize: (env.EVENT_IMAGE_SIZE ?? "2K").toUpperCase(),
  storyboardTimeoutMs: int("EVENT_STORYBOARD_TIMEOUT_MS", 60_000),
  imageTimeoutMs: int("EVENT_IMAGE_TIMEOUT_MS", 180_000),
  imageAttempts: int("EVENT_IMAGE_ATTEMPTS", 2),

  // 남용·비용 제한
  concurrency: Math.max(1, int("EVENT_CONCURRENCY", 1)),
  maxQueue: int("EVENT_MAX_QUEUE", 20),
  // 부스 기기는 모든 참여자가 같은 IP를 쓰므로 기본으로 끈다. 접근 차단은 Host Nginx 화이트리스트로 한다.
  perIpPerHour: int("EVENT_RATE_LIMIT_PER_HOUR", 0),
  dailyLimit: int("EVENT_DAILY_LIMIT", 300),
  dailyLimitZone: env.EVENT_DAILY_LIMIT_ZONE ?? "Asia/Seoul",
  accessCode: env.EVENT_ACCESS_CODE ?? "",
  resultTtlMs: int("EVENT_RESULT_TTL_MINUTES", 120) * 60_000,

  // 부스 운영: 입력 기기 여러 대 + 프린터 기기 1대. 완성 엽서는 인쇄 대기열로 보내 /print 화면이 자동 인쇄한다.
  printQueue: env.EVENT_PRINT_QUEUE === "true",
  printKey: env.EVENT_PRINT_KEY ?? "",
  printLeaseMs: int("EVENT_PRINT_LEASE_SECONDS", 120) * 1000,
  // 결과 화면을 보여 준 뒤 입력 화면으로 자동으로 돌아가는 시간. 0이면 돌아가지 않는다.
  resultResetSeconds: int("EVENT_RESULT_RESET_SECONDS", 10),

  diaryMinLength: 10,
  diaryMaxLength: 1000,
});

export function validateConfig() {
  if (!config.fakeAi && !config.project) {
    throw new Error("GOOGLE_CLOUD_PROJECT가 필요합니다. 로컬 확인은 EVENT_FAKE_AI=true로 실행합니다.");
  }
  if (config.imageSize && !["1K", "2K", "4K"].includes(config.imageSize)) {
    throw new Error(`EVENT_IMAGE_SIZE는 1K, 2K, 4K 중 하나이거나 비어 있어야 합니다: ${config.imageSize}`);
  }
  if (config.printQueue && config.printKey.length < 12) {
    throw new Error("EVENT_PRINT_QUEUE=true이면 12자 이상의 EVENT_PRINT_KEY가 필요합니다.");
  }
}
