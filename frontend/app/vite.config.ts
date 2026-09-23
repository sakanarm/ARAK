import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { resolve } from 'path';

/**
 * The vendored design system is consumed from SOURCE, not from a built dist.
 *
 * It lives in this repository (see frontend/ui-core-components/VENDORED.md) and
 * building it first would add a publish step between editing a token and seeing
 * it, for no benefit: Vite compiles its TypeScript exactly as it compiles ours,
 * and Tailwind 4 needs to scan those files for class names anyway.
 */
const uiCore = resolve(__dirname, '../ui-core-components/src');

/**
 * Resolves the vendored design system's own dependencies.
 *
 * Those files sit outside this package, so Node's walk up from them never
 * reaches a node_modules and `import 'react'` fails. Rather than duplicate an
 * install or alias each package by hand, re-resolve any bare import from a
 * vendored file as if this package had written it. tsconfig `paths` and Jest
 * `modulePaths` say the same thing to the other two resolvers.
 */
const appEntry = resolve(__dirname, 'src/main.tsx');

// Rollup ids use forward slashes even on Windows, where resolve() does not.
const uiCoreId = uiCore.replace(/\\/g, '/');

const vendoredDependencies: Plugin = {
  name: 'dac:vendored-dependencies',
  enforce: 'pre',
  async resolveId(source, importer) {
    if (!importer || !importer.replace(/\\/g, '/').startsWith(uiCoreId)) {
      return null;
    }
    if (/^[./]/.test(source) || source.startsWith('@/')) return null;
    const resolved = await this.resolve(source, appEntry, { skipSelf: true });
    return resolved?.id ?? null;
  },
};

export default defineConfig({
  /**
   * Where the app is mounted.
   *
   * "/" in development and on a host of its own. Set to "/Arak/" when building
   * for a proxy that routes several applications by path prefix, so that the
   * emitted index.html asks for "/Arak/assets/..." rather than for a path that
   * belongs to a different application. src/basePath.ts reads it back at
   * runtime through a meta tag, and the service logs an error at startup if the
   * bundle and its mount point disagree.
   */
  base: process.env.VITE_BASE ?? '/',
  plugins: [vendoredDependencies, react(), tailwindcss()],
  resolve: {
    alias: [
      { find: '@openmetadata/ui-core-components/colors', replacement: resolve(uiCore, 'colors') },
      { find: '@openmetadata/ui-core-components/components', replacement: resolve(uiCore, 'components') },
      { find: '@openmetadata/ui-core-components/utils', replacement: resolve(uiCore, 'utils') },
      { find: '@openmetadata/ui-core-components', replacement: uiCore },
      // The vendored sources import themselves through '@/...'.
      { find: /^@\/(.*)/, replacement: resolve(uiCore, '$1') },
      { find: '~', replacement: resolve(__dirname, 'src') },
    ],
  },
  server: {
    port: 3000,
    proxy: {
      // The Dropwizard service, so the dev server needs no CORS configuration
      // and the app uses the same relative paths it will use in production.
      '/api': {
        target: process.env.DAC_API_URL ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
    target: 'es2022',
  },
});
