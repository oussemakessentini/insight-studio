import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  // The browser calls same-origin /api/...; Vite forwards it to the Spring Boot API.
  const proxy = {
    '/api': {
      target: env.API_PROXY_TARGET || 'http://localhost:8080',
      changeOrigin: true,
    },
  }

  return {
    plugins: [react()],
    server: { port: 5173, proxy },
    preview: { port: 4173, proxy },
    build: {
      rolldownOptions: {
        output: {
          // Long-lived vendor chunks cache separately from app code.
          codeSplitting: {
            groups: [
              { name: 'react', test: /node_modules[\\/](react|react-dom|scheduler)[\\/]/, priority: 2 },
              { name: 'vendor', test: /node_modules[\\/]/, priority: 1 },
            ],
          },
        },
      },
    },
  }
})
