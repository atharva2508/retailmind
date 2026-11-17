import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Dev server on 3000 (the origin the API's CORS allowlist expects). /api is proxied to the
// Spring Boot app, so the browser only talks to one origin and no CORS round trip is needed.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    strictPort: true,
    proxy: { "/api": "http://localhost:8080" },
  },
});
