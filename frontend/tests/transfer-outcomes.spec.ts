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

test.describe("Transfer outcome narrative", () => {
  test.beforeEach(async ({ page, request }) => {
    const backendHealth = await request.get("/api/demo/accounts");
    test.skip(
      !backendHealth.ok(),
      "Backend demo API is not reachable for transfer outcomes E2E."
    );

    await page.goto("/transfers/new");
    await expect(page.getByText("Internal Transfer")).toBeVisible();
  });

  test("shows transfer impact summary and links to activity/dashboard", async ({
    page,
  }) => {
    const reseedResponse = page.waitForResponse(
      (response) =>
        response.url().includes("/api/demo/setup") &&
        response.request().method() === "POST"
    );
    await clickSetupAction(page);
    expect((await reseedResponse).ok()).toBeTruthy();

    await page.getByRole("combobox", { name: "From Account" }).click();
    await page.getByRole("option").first().click();

    await page.getByRole("combobox", { name: "To Account" }).click();
    await page.getByRole("option").first().click();

    await page.getByLabel("Amount (VND)").fill("110000");
    await page
      .getByLabel("Description (optional)")
      .fill("Transfer outcome narrative E2E");

    const transferResponsePromise = page.waitForResponse(
      (response) =>
        response.url().includes("/api/demo/transfers/internal") &&
        response.request().method() === "POST"
    );

    await page.getByRole("button", { name: "Transfer" }).click();

    const transferResponse = await transferResponsePromise;
    expect(transferResponse.ok()).toBeTruthy();
    const transferPayload = (await transferResponse.json()) as {
      sourceAccountId: string;
      destinationAccountId: string;
      journalId: string;
    };

    const summary = page.getByTestId("transfer-impact-summary");
    await expect(summary).toBeVisible();
    await expect(summary).toContainText("Transfer completed");
    await expect(summary).toContainText("Status:");
    await expect(summary).toContainText("Journal:");
    await expect(summary).toContainText("Source account");
    await expect(summary).toContainText("Destination account");
    await expect(summary).toContainText("Delta:");

    const sourceActivityLink = summary.getByRole("link", {
      name: "Open source activity",
    });
    const destinationActivityLink = summary.getByRole("link", {
      name: "Open destination activity",
    });
    const dashboardLink = summary.getByRole("link", {
      name: "Open dashboard outcomes",
    });

    await expect(sourceActivityLink).toHaveAttribute(
      "href",
      `/accounts/${transferPayload.sourceAccountId}`
    );
    await expect(destinationActivityLink).toHaveAttribute(
      "href",
      `/accounts/${transferPayload.destinationAccountId}`
    );
    await expect(dashboardLink).toHaveAttribute("href", "/dashboard");

    const destinationHref = await destinationActivityLink.getAttribute("href");
    if (!destinationHref) {
      throw new Error("Destination activity CTA is missing href.");
    }

    await sourceActivityLink.click();
    await expect(page).toHaveURL(
      new RegExp(`/accounts/${transferPayload.sourceAccountId}$`)
    );
    await expect(page.getByText("Transfer Out").first()).toBeVisible();
    await expect(
      page.getByText("Posted transfer debit from this account.").first()
    ).toBeVisible();

    await page.goto(destinationHref);
    await expect(page).toHaveURL(
      new RegExp(`/accounts/${transferPayload.destinationAccountId}$`)
    );
    await expect(page.getByText("Transfer In").first()).toBeVisible();
    await expect(
      page.getByText("Posted transfer credit to this account.").first()
    ).toBeVisible();

    await page.goto("/dashboard");
    await expect(page.getByText("Outcome highlights")).toBeVisible();
    await expect(page.getByTestId("recent-activity-list")).toContainText(
      "Transfer Completed"
    );
  });
});
