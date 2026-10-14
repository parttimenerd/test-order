import path from 'node:path'
import Components from 'unplugin-vue-components/vite'
import { defineConfig } from 'vite'

const deckRoot = import.meta.dirname

export default defineConfig({
  plugins: [
    Components({
      dirs: [
        path.resolve(deckRoot, '../components/common'),
        path.resolve(deckRoot, '../components/conference-60min'),
      ],
      dts: false,
      include: [/\.vue$/, /\.vue\?vue/, /\.md$/],
    }),
  ],
  resolve: {
    alias: [
      { find: /^\/components\/DemoCue\.vue$/, replacement: path.resolve(deckRoot, '../components/common/DemoCue.vue') },
      { find: /^\/components\/(.*)$/, replacement: `${path.resolve(deckRoot, '../components/conference-60min')}/$1` },
      { find: 'd3', replacement: path.resolve(deckRoot, 'node_modules/d3/src/index.js') },
      { find: 'vue', replacement: path.resolve(deckRoot, 'node_modules/vue/dist/vue.runtime.esm-bundler.js') },
    ],
  },
  server: {
    fs: {
      allow: [path.resolve(deckRoot, '..')],
    },
  },
  build: {
    cssMinify: false,
  },
})
