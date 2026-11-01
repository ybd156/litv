import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 部署约束（见 docs/design/00-总体方案.md §3.3）：
 * 飞牛统一网关模式下，管理台必须挂在 /app/litv/ 子路径下，且 base 结尾必须带斜杠，
 * 否则白屏 / 404。路由用 hash 模式，静态资源全部走 /app/litv/assets/*。
 */
export default defineConfig(({ mode }) => ({
  base: '/app/litv/',
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5273,
    host: true,
    // 本地开发时把网关前缀也代理掉，这样前端代码里的相对路径 api/v1 在 dev 下同样成立
    proxy: {
      '/app/litv/api/v1': {
        target: 'http://127.0.0.1:9527',
        changeOrigin: true,
        rewrite: (p: string) => p.replace('/app/litv', ''),
      },
      '/api/v1': {
        target: 'http://127.0.0.1:9527',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
    target: 'es2020',
    cssCodeSplit: false,
    sourcemap: false,
    // naive-ui 单块本来就大，gzip 后仍在预算内（见 README 体积表）
    chunkSizeWarningLimit: 1200,
    rollupOptions: {
      output: {
        manualChunks: {
          vendor: ['vue', 'vue-router'],
          ui: ['naive-ui'],
        },
      },
    },
  },
  define: {
    __MOCK_MODE__: JSON.stringify(mode === 'mock'),
  },
}))
