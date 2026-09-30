import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

// https://vite.dev/config/
export default defineConfig(({ mode, command }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const apiTarget = env.API_PROXY_TARGET || 'http://localhost:8080'
  // The browser calls same-origin /api/...; Vite forwards it to the Spring Boot API.
  const proxy = {
    '/api': {
      target: apiTarget,
      changeOrigin: true,
    },
  }

  return {
    plugins: [react()],
    define: {
      // Where the dev server forwards /api, named in "API unreachable" messages. Production
      // builds have no Vite proxy, so they report the page's own /api address instead.
      __API_PROXY_TARGET__: JSON.stringify(command === 'serve' ? apiTarget : null),
    },
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
