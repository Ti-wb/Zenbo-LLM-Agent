import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { viteSingleFile } from 'vite-plugin-singlefile'

// https://vite.dev/config/
export default defineConfig({
  base: './',
  plugins: [
    vue({
      template: {
        compilerOptions: {
          isCustomElement: (tag) =>
            ['tool', 'context', 'prop', 'array', 'dict'].includes(tag),
        },
      },
    }),
    // Inline JS/CSS/assets so `npm run build` produces a single HTML file
    viteSingleFile(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    },
  },
  test: {
    include: ['src/**/*.test.js'],
  },
  build: {
    // Avoid code-splitting so we only get a single JS bundle before inlining
    rollupOptions: {
      output: {
        inlineDynamicImports: true,
        manualChunks: undefined,
      },
    },
  },
})
