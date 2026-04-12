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

async function runTransfer(page: Page, amountMajor: number) {
  await page.goto("/transfers/new");
  await expect(page.getByText("Internal Transfer")).toBeVisible();

  await page.getByRole("combobox", { name: "From Account" }).click();
  await page.getByRole("option").first().click();

  await page.getByRole("combobox", { name: "To Account" }).click();
  await page.getByRole("option").first().click();

  await page.getByLabel("Amount (VND)").fill(String(amountMajor));
  await page.getByLabel("Description (optional)").fill(
    "Dashboard narrative transfer"
  );

  const transferResponse = page.waitForResponse(
    (response) =>
      response.url().includes("/api/demo/transfers/internal") &&
      response.request().method() === "POST"
  );
  await page.getByRole("button", { name: "Transfer" }).click();
  expect((await transferResponse).ok()).toBeTruthy();
  await expect(page.getByTestId("transfer-impact-summary")).toBeVisible();
  await expect(page.getByText("Transfer completed")).toBeVisible();
}

async function runPaymentCapture(page: Page, amountMajor: number) {
  await page.goto("/payments");
  await expect(page.getByText("Payment Holds")).toBeVisible();

  await page.getByRole("tab", { name: "Authorize Hold" }).click();
  await page.getByRole("combobox", { name: "To Account (payee)" }).click();
  await page.getByRole("option").first().click();
  await page.getByLabel("Amount (VND)").fill(String(amountMajor));

  const authorizeResponse = page.waitForResponse(
    (response) =>
      response.url().includes("/api/demo/payments/authorize") &&
      response.request().method() === "POST"
  );
  await page.getByRole("button", { name: "Authorize Hold" }).click();
  expect((await authorizeResponse).ok()).toBeTruthy();
  await expect(page.getByText("Hold authorized")).toBeVisible();

  await page.getByRole("tab", { name: "Active Holds" }).click();
  await expect(page.getByRole("button", { name: "Capture" }).first()).toBeVisible();

  const captureResponse = page.waitForResponse(
    (response) =>
      response.url().includes("/api/demo/payments/capture") &&
      response.request().method() === "POST"
  );
  await page.getByRole("button", { name: "Capture" }).first().click();
  await page.getByRole("button", { name: "Confirm" }).click();
  expect((await captureResponse).ok()).toBeTruthy();
  await expect(page.getByText("Hold captured")).toBeVisible();
}

test.describe("Dashboard outcomes narrative", () => {
  test.beforeEach(async ({ page, request }) => {
    const backendHealth = await request.get("/api/demo/accounts");
    test.skip(
      !backendHealth.ok(),
      "Backend demo API is not reachable for dashboard outcomes E2E."
    );

    await page.goto("/dashboard");
  });

  test("shows payment + transfer outcomes and filters aggregated feed", async ({
    page,
  }) => {
    const reseedResponse = page.waitForResponse(
      (response) =>
        response.url().includes("/api/demo/setup") &&
        response.request().method() === "POST"
    );
    await clickSetupAction(page);
    expect((await reseedResponse).ok()).toBeTruthy();

    await runTransfer(page, 100_000);
    await runPaymentCapture(page, 150_000);

    await page.goto("/dashboard");
    await expect(page.getByText("Outcome highlights")).toBeVisible();

    const activityList = page.getByTestId("recent-activity-list");
    await expect(activityList).toContainText("Transfer Completed");
    await expect(activityList).toContainText(/Hold (Authorized|Captured|Voided)/);

    await page.getByRole("button", { name: "Payments" }).click();
    await expect(activityList).toContainText(/Hold (Authorized|Captured|Voided)/);
    await expect(activityList).not.toContainText("Transfer Completed");

    await page.getByRole("button", { name: "Transfers" }).click();
    await expect(activityList).toContainText("Transfer Completed");
    await expect(activityList).not.toContainText("Hold Authorized");
    await expect(activityList).not.toContainText("Hold Captured");
    await expect(activityList).not.toContainText("Hold Voided");

    await page.getByRole("button", { name: "All" }).click();
    await page.getByRole("link", { name: "View all" }).click();
    await expect(page).toHaveURL(/\/accounts$/);
  });
});
