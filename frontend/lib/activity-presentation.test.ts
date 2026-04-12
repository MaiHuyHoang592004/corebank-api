import { describe, expect, it } from "vitest";
import {
  extractActivityAmount,
  parseActivityPayload,
  resolveActivityPresentation,
} from "./activity-presentation";

describe("activity payload parsing", () => {
  it("parses valid payload JSON and returns null for invalid JSON", () => {
    expect(parseActivityPayload('{"amountMinor":1000}')).toEqual({
      amountMinor: 1000,
    });
    expect(parseActivityPayload("not-json")).toBeNull();
    expect(parseActivityPayload(null)).toBeNull();
  });

  it("extracts amount and currency with fallback", () => {
    expect(
      extractActivityAmount({ amountMinor: 120000, currency: "VND" })
    ).toEqual({
      amount: 120000,
      currency: "VND",
    });
    expect(
      extractActivityAmount({ amountMajor: 35000, currency: " " })
    ).toEqual({
      amount: 35000,
      currency: "VND",
    });
    expect(extractActivityAmount({})).toBeNull();
    expect(extractActivityAmount(null)).toBeNull();
  });
});

describe("activity presentation semantics", () => {
  it("maps transfer source to debit and destination to credit", () => {
    const payload = {
      sourceAccountId: "acc-source",
      destinationAccountId: "acc-destination",
    };

    expect(
      resolveActivityPresentation("TRANSFER_COMPLETED", payload, "acc-source")
    ).toMatchObject({
      label: "Transfer Out",
      amountDirection: "debit",
      impactHint: "Posted transfer debit from this account.",
    });

    expect(
      resolveActivityPresentation(
        "TRANSFER_COMPLETED",
        payload,
        "acc-destination"
      )
    ).toMatchObject({
      label: "Transfer In",
      amountDirection: "credit",
      impactHint: "Posted transfer credit to this account.",
    });
  });

  it("maps transfer unknown account context to neutral", () => {
    expect(
      resolveActivityPresentation(
        "TRANSFER_COMPLETED",
        {
          sourceAccountId: "acc-1",
          destinationAccountId: "acc-2",
        },
        "acc-3"
      )
    ).toMatchObject({
      label: "Transfer",
      amountDirection: "neutral",
      impactHint: "Transfer direction is unknown for this account context.",
    });
  });

  it("maps hold event types to requested semantics", () => {
    expect(resolveActivityPresentation("HOLD_AUTHORIZED", null, "acc-1")).toMatchObject({
      label: "Hold Authorized",
      amountDirection: "neutral",
      impactHint: "Available balance reserved by hold. Posted balance unchanged.",
    });

    expect(resolveActivityPresentation("HOLD_CAPTURED", null, "acc-1")).toMatchObject({
      label: "Hold Captured",
      amountDirection: "debit",
      impactHint: "Hold captured into posted debit.",
    });

    expect(resolveActivityPresentation("HOLD_VOIDED", null, "acc-1")).toMatchObject({
      label: "Hold Voided",
      amountDirection: "credit",
      impactHint: "Hold released; available balance restored.",
    });
  });

  it("keeps fallback neutral for out-of-scope event types", () => {
    expect(resolveActivityPresentation("LOAN_DISBURSED", null, "acc-1")).toMatchObject({
      label: "Loan Disbursed",
      amountDirection: "neutral",
      impactHint: null,
    });
  });
});
