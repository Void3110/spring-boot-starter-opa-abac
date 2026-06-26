import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// Dev server on :3000. The SPA is single-origin against the gateway (:9085) in production, but in
// dev we run on :3000 and proxy the gateway-fronted paths so the browser stays same-origin and CORS
// is a non-issue. Everything the SPA calls — the app API (/api), and the gateway-proxied Keycloak
// (/realms, /resources) — is forwarded to APISIX on :9085. See infra/README.md "Demo SPA auth".
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 3000,
    proxy: {
      '/api': { target: 'http://localhost:9085', changeOrigin: true },
      '/realms': { target: 'http://localhost:9085', changeOrigin: true },
      '/resources': { target: 'http://localhost:9085', changeOrigin: true },
    },
  },
})
