import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

const proxyTarget =
  process.env.MCP_SERVER_URL ?? process.env.OPENAI_PROXY_URL ?? 'http://localhost:1234'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/v1': {
        target: proxyTarget,
        changeOrigin: true,
        secure: false,
      },
    },
  },
})
