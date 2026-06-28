// Updated vite.config.ts — adds nitro: true to force Vercel deployment plugin
// This fixes "No Lovable context detected — skipping nitro deploy plugin"
import { defineConfig } from "@lovable.dev/vite-tanstack-config";

export default defineConfig({
  tanstackStart: {
    server: { entry: "server" },
  },
  nitro: true,
});
