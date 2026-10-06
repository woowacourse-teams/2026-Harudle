import { defineConfig } from '@playwright/test';

export default defineConfig({
  reporter: [['html', { open: 'never' }]],
  testDir: './e2e',
  use: {
    baseURL: 'http://localhost:5173',
  },
  webServer: {
    command: 'pnpm exec webpack serve --mode development --no-open',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
  },
});
