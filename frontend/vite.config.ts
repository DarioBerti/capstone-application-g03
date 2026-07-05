/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The browser talks only to this dev server; Vite proxies each /api/<svc> prefix to the
// matching local JVM, so there are no CORS edits in the services. Ports match the
// configuration repo's `just dev-env` / `dev-env-files` (catalog 8083, orders 8080, ...).
// Override a port with e.g. ORDERS_PORT=9000 just dev.
const port = (name: string, fallback: number) => `http://localhost:${process.env[name] ?? fallback}`

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api/catalog': port('CATALOG_PORT', 8083),
      '/api/orders': port('ORDERS_PORT', 8080),
      '/api/inventory': port('INVENTORY_PORT', 8081),
      '/api/payments': port('PAYMENTS_PORT', 8082),
      '/api/users': port('USERS_PORT', 8085),
      // The SSE stream that pushes booking updates to the browser (http-proxy streams text/event-stream as-is).
      '/api/notifications': port('NOTIFICATIONS_PORT', 8084),
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    css: false,
  },
})
