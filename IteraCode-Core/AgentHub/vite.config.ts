import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import { resolve } from "path";
import AutoImport from "unplugin-auto-import/vite";
import Components from "unplugin-vue-components/vite";

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({
      imports: ["vue", "vue-router"],
      dts: "src/auto-import.d.ts",
    }),
    Components({
      dts: "src/components.d.ts",
    }),
  ],
  resolve: {
    alias: {
      "@": resolve(__dirname, "./src"),
    },
  },
  server: {
    port: 8088,
    proxy: {
      "/api": {
        changeOrigin: true,
        target: "http://localhost:8989",
      },
    },
  },
  base: "./",
  build: {
    outDir: "dist",
    emptyOutDir: true,
  },
});
