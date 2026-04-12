import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    include: ["lib/**/*.test.ts"],
    exclude: ["tests/**", "node_modules/**"],
  },
});
