import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

const proxyTarget =
  process.env.MCP_SERVER_URL ?? process.env.OPENAI_PROXY_URL ?? 'http://localhost:1234'
const backendTarget = process.env.BACKEND_API_URL ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: backendTarget,
        changeOrigin: true,
        secure: false,
      },
      '/v1': {
        target: proxyTarget,
        changeOrigin: true,
        secure: false,
      },
    },
  },
})
