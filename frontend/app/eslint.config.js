// Flat config (ESLint 9). Scope is deliberately narrow: this repo's own source.
//
// frontend/ui-core-components is a vendored copy of OpenMetadata's design
// system at a pinned tag. Linting it would report someone else's style choices
// on every run and the fixes would be wiped by the next resync, so it is
// ignored here the same way CI refuses hand edits to it.
import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import react from 'eslint-plugin-react';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';

export default tseslint.config(
  {
    ignores: [
      'dist/**',
      'node_modules/**',
      // Both halves of the codegen pipeline. Generated code is not edited, so
      // there is nothing useful to report about it.
      'src/generated/**',
      '../ui-core-components/**',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['src/**/*.{ts,tsx}', 'e2e/**/*.ts'],
    languageOptions: {
      parserOptions: { ecmaFeatures: { jsx: true } },
      globals: { ...globals.browser, ...globals.es2022 },
    },
    plugins: { react, 'react-hooks': reactHooks },
    settings: { react: { version: '18.2' } },
    rules: {
      ...reactHooks.configs.recommended.rules,
      // React 18 + the automatic JSX runtime: importing React is not required.
      'react/react-in-jsx-scope': 'off',
      'react/prop-types': 'off',
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
      // Warn rather than error: the OM client is generated and its surface is
      // wide enough that a hard failure here would block work on day one.
      '@typescript-eslint/no-explicit-any': 'warn',
    },
  },
  {
    files: ['scripts/**/*.mjs', '**/*.cjs', '*.config.{ts,js}'],
    languageOptions: { globals: { ...globals.node } },
    rules: { '@typescript-eslint/no-require-imports': 'off' },
  },
);
