import { describe, expect, it } from "vitest";
import {
  aggregateDashboardActivity,
  computeOutcomeHighlights,
  createAccountNumberLookup,
  type DashboardActivityItemVm,
} from "./dashboard-activity";
import type { DemoActivityItem } from "./api";

function makeActivity(
  eventId: string,
  eventType: string,
  occurredAt: string | null
): DemoActivityItem {
  return {
    eventId,
    eventType,
    occurredAt,
    actor: "demo_user",
    payloadJson: null,
  };
}

describe("aggregateDashboardActivity", () => {
  it("merges multi-account activity and dedupes by newest event timestamp", () => {
    const result = aggregateDashboardActivity([
      {
        accountId: "acct-a",
        items: [
          makeActivity(
            "evt-1",
            "HOLD_AUTHORIZED",
            "2026-04-04T09:00:00.000Z"
          ),
        ],
      },
      {
        accountId: "acct-b",
        items: [
          makeActivity(
            "evt-1",
            "HOLD_CAPTURED",
            "2026-04-04T10:00:00.000Z"
          ),
          makeActivity(
            "evt-2",
            "TRANSFER_COMPLETED",
            "2026-04-04T11:00:00.000Z"
          ),
        ],
      },
    ]);

    expect(result).toHaveLength(2);
    expect(result[0].eventId).toBe("evt-2");
    expect(result[1].eventId).toBe("evt-1");
    expect(result[1].eventType).toBe("HOLD_CAPTURED");
    expect(result[1].accountId).toBe("acct-b");
  });

  it("sorts records with null occurredAt to the end", () => {
    const result = aggregateDashboardActivity([
      {
        accountId: "acct-a",
        items: [
          makeActivity("evt-1", "TRANSFER_COMPLETED", null),
          makeActivity("evt-2", "HOLD_AUTHORIZED", "2026-04-04T12:00:00.000Z"),
        ],
      },
    ]);

    expect(result.map((item) => item.eventId)).toEqual(["evt-2", "evt-1"]);
  });

  it("enforces limit after merge and sort", () => {
    const result = aggregateDashboardActivity(
      [
        {
          accountId: "acct-a",
          items: [
            makeActivity("evt-1", "TRANSFER_COMPLETED", "2026-04-04T10:00:00.000Z"),
            makeActivity("evt-2", "HOLD_AUTHORIZED", "2026-04-04T11:00:00.000Z"),
            makeActivity("evt-3", "HOLD_CAPTURED", "2026-04-04T12:00:00.000Z"),
          ],
        },
      ],
      2
    );

    expect(result).toHaveLength(2);
    expect(result.map((item) => item.eventId)).toEqual(["evt-3", "evt-2"]);
  });
});

describe("computeOutcomeHighlights", () => {
  it("counts payment and transfer outcomes from aggregated feed", () => {
    const items: DashboardActivityItemVm[] = [
      { ...makeActivity("evt-1", "HOLD_AUTHORIZED", "2026-04-04T09:00:00.000Z"), accountId: "acct-a" },
      { ...makeActivity("evt-2", "HOLD_CAPTURED", "2026-04-04T10:00:00.000Z"), accountId: "acct-a" },
      { ...makeActivity("evt-3", "HOLD_VOIDED", "2026-04-04T11:00:00.000Z"), accountId: "acct-b" },
      { ...makeActivity("evt-4", "TRANSFER_COMPLETED", "2026-04-04T12:00:00.000Z"), accountId: "acct-b" },
      { ...makeActivity("evt-5", "ACCOUNT_CREATED", "2026-04-04T13:00:00.000Z"), accountId: "acct-b" },
    ];

    expect(computeOutcomeHighlights(items)).toEqual({
      authorizedCount: 1,
      capturedCount: 1,
      voidedCount: 1,
      transferCount: 1,
    });
  });
});

describe("createAccountNumberLookup", () => {
  it("maps accountId to accountNumber for dashboard row context", () => {
    const lookup = createAccountNumberLookup([
      {
        accountId: "acct-a",
        customerId: "cust-a",
        customerName: "Demo A",
        productId: "prod-a",
        productCode: "CHK",
        productName: "Checking",
        productType: "DDA",
        accountNumber: "100000000001",
        currency: "VND",
        status: "ACTIVE",
        postedBalanceMinor: 1000000,
        availableBalanceMinor: 900000,
      },
      {
        accountId: "acct-b",
        customerId: "cust-b",
        customerName: "Demo B",
        productId: "prod-b",
        productCode: "SAV",
        productName: "Savings",
        productType: "SAVINGS",
        accountNumber: "100000000002",
        currency: "VND",
        status: "ACTIVE",
        postedBalanceMinor: 2000000,
        availableBalanceMinor: 2000000,
      },
    ]);

    expect(lookup).toEqual({
      "acct-a": "100000000001",
      "acct-b": "100000000002",
    });
  });
});
