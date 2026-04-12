import { expect, type Page, test } from "@playwright/test";

async function selectPayeeAccount(page: Page) {
  await page.getByRole("combobox", { name: "To Account (payee)" }).click();
  await page.getByRole("option").first().click();
}

async function authorizeHold(page: Page, amountMajor: number) {
  await page.getByRole("tab", { name: "Authorize Hold" }).click();
  await selectPayeeAccount(page);
  await page.getByLabel("Amount (VND)").fill(String(amountMajor));

  const authorizeResponse = page.waitForResponse(
    (response) =>
      response.url().includes("/api/demo/payments/authorize") &&
      response.request().method() === "POST"
  );

  await page.getByRole("button", { name: "Authorize Hold" }).click();

  const response = await authorizeResponse;
  expect(response.ok()).toBeTruthy();
}

test.describe("Payments demo regression", () => {
  test.beforeEach(async ({ page, request }) => {
    await page.goto("/payments");

    const backendHealth = await request.get("/api/demo/accounts");
    test.skip(
      !backendHealth.ok(),
      "Backend demo API is not reachable for payments E2E."
    );
  });

  test("reseed -> authorize -> capture/void -> activity + cross-page smoke", async ({
    page,
  }) => {
    const reseedResponse = page.waitForResponse(
      (response) =>
        response.url().includes("/api/demo/setup") &&
        response.request().method() === "POST"
    );
    await page.getByRole("button", { name: "Re-seed demo data" }).click();
    expect((await reseedResponse).ok()).toBeTruthy();

    await expect(page.getByText("Payment Holds")).toBeVisible();

    await authorizeHold(page, 500_000);
    await expect(page.getByText("Hold authorized")).toBeVisible();
    await expect(
      page.getByText("Available balance (before → after)")
    ).toBeVisible();
    await expect(page.getByText("Posted balance (before → after)")).toBeVisible();

    await expect(page.getByRole("tab", { name: "Active Holds" })).toBeVisible();
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

    await authorizeHold(page, 250_000);
    await expect(page.getByText("Hold authorized")).toBeVisible();

    const voidResponse = page.waitForResponse(
      (response) =>
        response.url().includes("/api/demo/payments/void") &&
        response.request().method() === "POST"
    );
    page.once("dialog", (dialog) => dialog.accept());
    await page.getByRole("button", { name: "Void" }).first().click();
    expect((await voidResponse).ok()).toBeTruthy();
    await expect(page.getByText("Hold voided")).toBeVisible();

    await page.getByRole("link", { name: "Open account activity" }).click();
    await expect(page).toHaveURL(/\/accounts\/[0-9a-f-]+/);
    await expect(page.getByText("Hold Captured").first()).toBeVisible();
    await expect(page.getByText("Hold Voided").first()).toBeVisible();
    await expect(
      page.getByText("Hold captured into posted debit.").first()
    ).toBeVisible();
    await expect(
      page.getByText("Hold released; available balance restored.").first()
    ).toBeVisible();

    await page.goto("/dashboard");
    await expect(page.getByText("Welcome")).toBeVisible();

    await page.goto("/accounts");
    await expect(page.getByText("Your Accounts")).toBeVisible();

    await page.goto("/transfers/new");
    await expect(page.getByText("Internal Transfer")).toBeVisible();

    await expect(page.locator("body")).not.toContainText("Appwrite");
    await expect(page.locator("body")).not.toContainText("Plaid");
    await expect(page.locator("body")).not.toContainText("Dwolla");
  });
});
