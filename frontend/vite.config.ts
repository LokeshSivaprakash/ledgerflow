import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// `npm run dev` proxies /api to the Spring Boot app so the browser sees one origin (no CORS).
// `npm run build` writes into the Spring Boot static folder; the jar then serves the UI itself.
export default defineConfig({
  plugins: [react()],
  server: { proxy: { '/api': 'http://localhost:8080' } },
  build: { outDir: '../src/main/resources/static', emptyOutDir: true },
});
