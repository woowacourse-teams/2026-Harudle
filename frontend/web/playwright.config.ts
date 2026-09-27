import { defineConfig } from '@playwright/test';
import { NOTICE_ACKNOWLEDGED_STORAGE_STATE } from './e2e/storageState';

export default defineConfig({
  reporter: [['html', { open: 'never' }]],
  testDir: './e2e',
  use: {
    baseURL: 'http://localhost:5173',
    storageState: NOTICE_ACKNOWLEDGED_STORAGE_STATE,
  },
  webServer: {
    command: 'pnpm exec webpack serve --mode development --no-open',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
  },
});
