import { defineConfig } from 'jest';

export default defineConfig({
  testEnvironment: 'jsdom',
  setupFilesAfterEnv: ['<rootDir>/jest.setup.ts'],
  moduleNameMapper: {
    '\\.(mp4|jpg)$': '<rootDir>/src/test/fileMock.cjs',
  },
  testPathIgnorePatterns: ['/node_modules/', '<rootDir>/e2e/'],
  clearMocks: true,
});
