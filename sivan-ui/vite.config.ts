import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: {
    chunkSizeWarningLimit: 800, // Shiki 语言语法文件通过动态 import 懒加载，不影响首屏
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on('proxyReq', (_proxyReq, req) => {
            // SSE 长连接不走代理缓存
            if (req.headers.accept === 'text/event-stream') {
              _proxyReq.setHeader('Connection', 'keep-alive')
              _proxyReq.setHeader('Cache-Control', 'no-cache')
            }
          })
        },
      },
    },
  },
})
