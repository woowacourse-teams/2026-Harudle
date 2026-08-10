import { defineConfig } from 'jest';

export default defineConfig({
  testEnvironment: 'jsdom',
  setupFilesAfterEnv: ['<rootDir>/src/test/setupTests.ts'],
  clearMocks: true,
});
