import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// base "./" so the page works under https://<user>.github.io/inkcalidraw/
export default defineConfig({ plugins: [react()], base: "./", build: { chunkSizeWarningLimit: 4000 } });
