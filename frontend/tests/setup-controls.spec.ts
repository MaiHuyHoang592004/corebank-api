import { expect, type Page, test } from "@playwright/test";

async function clickSetupAction(page: Page) {
  const initializeButton = page.getByRole("button", {
    name: "Initialize demo data",
  });
  if (
    (await initializeButton.count()) > 0 &&
    (await initializeButton.first().isVisible())
  ) {
    await initializeButton.first().click();
    return;
  }

  await page.getByRole("button", { name: "Re-seed demo data" }).first().click();
}

async function mockSetupResponse(
  page: Page,
  status: number,
  body: Record<string, unknown>
) {
  await page.route("**/api/demo/setup", async (route) => {
    if (route.request().method() !== "POST") {
      await route.fallback();
      return;
    }

    await route.fulfill({
      status,
      contentType: "application/json",
      body: JSON.stringify(body),
    });
  });
}

async function assertReadinessCard(page: Page, path: string) {
  await page.goto(path);
  await expect(page.getByText("Demo setup status")).toBeVisible();
  await expect(page.locator("body")).not.toContainText(
    "Make sure the CoreBank backend is running on port 9090"
  );
}

test.describe("Demo setup controls", () => {
  test.beforeEach(async ({ page, request }) => {
    const backendHealth = await request.get("/api/demo/accounts");
    test.skip(
      !backendHealth.ok(),
      "Backend demo API is not reachable for setup controls E2E."
    );

    const reseed = await request.post("/api/demo/setup");
    test.skip(
      !reseed.ok(),
      "Setup controls E2E requires setup credentials for baseline seed."
    );

    await page.goto("/dashboard");
    await expect(page.getByText("Demo setup status")).toBeVisible();
  });

  test("shows normalized 503 message from setup route", async ({ page }) => {
    await mockSetupResponse(page, 503, {
      message:
        "Setup credentials are not configured for this frontend. Configure CORE_BANK_SETUP_USER and CORE_BANK_SETUP_PASS.",
    });

    for (const path of ["/dashboard", "/payments", "/accounts", "/transfers/new"]) {
      await page.goto(path);
      await clickSetupAction(page);
      await expect(
        page.getByText("Setup credentials are not configured for this frontend.")
      ).toBeVisible();
    }
  });

  test("shows consistent forbidden message across demo pages", async ({
    page,
  }) => {
    await mockSetupResponse(page, 403, {
      message: "Demo setup requires OPS or ADMIN access.",
    });

    for (const path of ["/dashboard", "/payments", "/accounts", "/transfers/new"]) {
      await page.goto(path);
      await clickSetupAction(page);
      await expect(
        page.getByText("Demo setup requires OPS or ADMIN access.")
      ).toBeVisible();
    }
  });

  test("shows setup summary and next-action CTA after successful setup", async ({
    page,
  }) => {
    await mockSetupResponse(page, 200, {
      initializedAt: "2026-04-04T09:00:00Z",
      accountIds: {
        sourceAccountId: "20000000-0000-0000-0000-000000000001",
        destinationAccountId: "20000000-0000-0000-0000-000000000002",
      },
      sampleAmountsMinor: {
        paymentAmountMinor: 500000,
      },
    });

    await clickSetupAction(page);

    const successPanel = page
      .locator("div.rounded-lg.border.border-emerald-200")
      .filter({
        hasText: "Demo data re-seeded to baseline. Refreshing page...",
      });

    await expect(successPanel).toBeVisible();
    await expect(successPanel.getByText("Baseline initialized at:")).toBeVisible();
    await expect(
      successPanel.getByText("Suggested payment amount:")
    ).toBeVisible();
    await expect(
      successPanel.getByRole("link", { name: "Open Payments" })
    ).toBeVisible();
    await expect(
      successPanel.getByRole("link", { name: "Open Accounts" })
    ).toBeVisible();
    await expect(
      successPanel.getByRole("link", { name: "Open Activity" })
    ).toBeVisible();
  });

  test("shows readiness card consistently on dashboard, payments, accounts, and transfers", async ({
    page,
  }) => {
    await assertReadinessCard(page, "/dashboard");
    await assertReadinessCard(page, "/payments");
    await assertReadinessCard(page, "/accounts");
    await assertReadinessCard(page, "/transfers/new");

    await expect(page.getByText("Internal Transfer")).toBeVisible();
  });
});
