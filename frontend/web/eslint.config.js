import js from '@eslint/js';
import react from 'eslint-plugin-react';
import reactHooks from 'eslint-plugin-react-hooks';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  {
    ignores: ['dist', 'node_modules', 'playwright-report', 'test-results'],
  },

  js.configs.recommended,

  ...tseslint.configs.recommended,

  {
    files: ['**/*.{ts,tsx}'],

    ...react.configs.flat.recommended,
    ...react.configs.flat['jsx-runtime'],

    rules: {
      ...react.configs.flat['jsx-runtime'].rules,
      '@typescript-eslint/consistent-type-definitions': ['error', 'interface'],
    },

    settings: {
      react: {
        version: 'detect',
      },
    },
  },

  {
    // tsconfig.json의 타입 검사 대상에 타입 정보가 필요한 규칙을 적용한다.
    files: ['src/**/*.{ts,tsx}', 'jest.setup.ts'],

    languageOptions: {
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },

    rules: {
      '@typescript-eslint/no-unsafe-assignment': 'error',
      '@typescript-eslint/no-unsafe-argument': 'error',
      '@typescript-eslint/no-unsafe-call': 'error',
      '@typescript-eslint/no-unsafe-member-access': 'error',
      '@typescript-eslint/no-unsafe-return': 'error',
      '@typescript-eslint/no-non-null-assertion': 'error',
      '@typescript-eslint/use-unknown-in-catch-callback-variable': 'error',
    },
  },

  reactHooks.configs.flat.recommended,
);
