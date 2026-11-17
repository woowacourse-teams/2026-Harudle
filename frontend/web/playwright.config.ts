import { defineConfig } from '@playwright/test';

export default defineConfig({
  reporter: [['html', { open: 'never' }]],
  testDir: './e2e',
  // 모든 워커가 개발 서버 하나를 나눠 쓴다. 코어가 많은 로컬에서 워커가 늘면 생성 상태 E2E가 타이밍 때문에 실패하므로 CI 러너의 기본값과 맞춘다.
  workers: 2,
  use: {
    baseURL: 'http://localhost:5173',
  },
  webServer: {
    command: 'pnpm exec webpack serve --mode development --no-open',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
  },
});
