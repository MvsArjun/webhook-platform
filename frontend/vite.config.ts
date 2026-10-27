import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const apiProxy = {
  '/api': {
    target: process.env.E2E_API_TARGET ?? 'http://127.0.0.1:8080',
    changeOrigin: false,
  },
}

export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: apiProxy,
  },
  preview: { host: '127.0.0.1', proxy: apiProxy },
})
