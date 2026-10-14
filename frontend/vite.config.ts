import { defineConfig } from "vite";
const api = process.env.VITE_API_URL ?? "http://127.0.0.1:8080";
import vue from "@vitejs/plugin-vue";
export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      "/api": api,
      "/actuator": api,
    },
  },
});
