import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  define: {
    'process.env.NODE_ENV': JSON.stringify('production'),
  },
  build: {
    outDir: resolve(__dirname, '../resources/dashboard/dist'),
    emptyOutDir: true,
    lib: {
      entry: resolve(__dirname, 'src/main.ts'),
      name: 'TestOrderDashboard',
      formats: ['iife'],
      fileName: () => 'dashboard.js',
    },
    rollupOptions: {
      output: {
        assetFileNames: (assetInfo) => {
          if (assetInfo.name?.endsWith('.css')) return 'dashboard.css'
          return assetInfo.name ?? 'asset'
        },
      },
    },
    cssCodeSplit: false,
    assetsInlineLimit: 0,
    minify: 'esbuild',
  },
})
