import { defineConfig } from 'jest';

export default defineConfig({
  testEnvironment: 'jsdom',
  setupFilesAfterEnv: ['<rootDir>/src/test/setupTests.ts'],
  testPathIgnorePatterns: ['/node_modules/', '<rootDir>/e2e/'],
  clearMocks: true,
});
