import js from '@eslint/js'
import globals from 'globals'
import tseslint from 'typescript-eslint'
import pluginVue from 'eslint-plugin-vue'
import prettier from 'eslint-config-prettier'

export default tseslint.config(
  { ignores: ['dist', 'coverage', 'node_modules', 'src/shared/api/schema.ts'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    files: ['**/*.vue'],
    languageOptions: { parserOptions: { parser: tseslint.parser } },
  },
  {
    languageOptions: { globals: { ...globals.browser, ...globals.node } },
    rules: {
      '@typescript-eslint/no-explicit-any': 'error',
    },
  },
  {
    // shadcn-vue generated primitives use single-word names and optional `class` props
    files: ['src/shared/ui/**/*.vue'],
    rules: { 'vue/multi-word-component-names': 'off', 'vue/require-default-prop': 'off' },
  },
  prettier,
)
