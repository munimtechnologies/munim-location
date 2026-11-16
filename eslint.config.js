const js = require('@eslint/js')
const tseslint = require('typescript-eslint')
const reactHooks = require('eslint-plugin-react-hooks')
const prettierConfig = require('eslint-config-prettier')
const globals = require('globals')

module.exports = tseslint.config(
  {
    ignores: [
      'lib/**',
      'nitrogen/**',
      '**/node_modules/**',
      'example/ios/**',
      'example/android/**',
      'docs/**',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  prettierConfig,
  {
    plugins: { 'react-hooks': reactHooks },
    rules: {
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn',
    },
  },
  {
    files: ['**/*.js', '**/*.cjs', '**/*.mjs'],
    languageOptions: { globals: { ...globals.node } },
    rules: { '@typescript-eslint/no-require-imports': 'off' },
  },
  {
    files: ['src/**/*.ts', 'example/**/*.ts', 'example/**/*.tsx'],
    languageOptions: { globals: { ...globals.browser, ...globals.es2021 } },
  },
  {
    files: ['test/**'],
    languageOptions: { globals: { ...globals.jest, ...globals.node } },
  }
)
