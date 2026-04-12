import { defineConfig, devices } from "@playwright/test";

const baseURL = process.env.E2E_BASE_URL ?? "http://127.0.0.1:3000";

export default defineConfig({
  testDir: "./tests",
  testMatch: "**/*.spec.ts",
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  timeout: 180_000,
  expect: {
    timeout: 15_000,
  },
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL,
    trace: "on-first-retry",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: "npm run dev -- --hostname 127.0.0.1 --port 3000",
        url: `${baseURL}/dashboard`,
        cwd: __dirname,
        reuseExistingServer: true,
        timeout: 180_000,
        env: {
          ...process.env,
          CORE_BANK_URL: process.env.CORE_BANK_URL ?? "http://127.0.0.1:9090",
          CORE_BANK_USER: process.env.CORE_BANK_USER ?? "demo_user",
          CORE_BANK_PASS: process.env.CORE_BANK_PASS ?? "demo_user",
          CORE_BANK_SETUP_USER:
            process.env.CORE_BANK_SETUP_USER ?? "demo_admin",
          CORE_BANK_SETUP_PASS:
            process.env.CORE_BANK_SETUP_PASS ?? "demo_admin",
        },
      },
});
