import { defineConfig } from 'jest';

export default defineConfig({
  testEnvironment: 'jsdom',
  setupFilesAfterEnv: ['<rootDir>/jest.setup.ts'],
  testPathIgnorePatterns: ['/node_modules/', '<rootDir>/e2e/'],
  clearMocks: true,
});
