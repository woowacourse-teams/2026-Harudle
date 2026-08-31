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

    settings: {
      react: {
        version: 'detect',
      },
    },
  },

  reactHooks.configs.flat.recommended,
);
