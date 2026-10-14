/// <reference types="vitest/config" />
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// The SPA is served same-origin from Spring Boot in production: Vite builds into
// the backend's static dir and the emitted index.html becomes the welcome page.
// In dev we run Vite on :5173 and proxy /api + /ws to the backend on :8080 so the
// JSESSIONID cookie (which the GitHub OAuth flow is bound to) stays consistent.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: "../src/main/resources/static",
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      "/api": { target: "http://localhost:8080", changeOrigin: false },
      "/ws": { target: "ws://localhost:8080", ws: true, changeOrigin: false },
    },
  },
  test: {
    environment: "node",
    include: ["src/**/*.test.ts"],
  },
});
