export type ActivityAmountDirection = "debit" | "credit" | "neutral";

export interface ActivityPayload {
  amountMinor?: number;
  amountMajor?: number;
  currency?: string;
  sourceAccountId?: string;
  destinationAccountId?: string;
}

export interface ActivityPresentationVm {
  label: string;
  amountDirection: ActivityAmountDirection;
  impactHint: string | null;
}

const EVENT_TYPE_LABELS: Record<string, string> = {
  ACCOUNT_CREATED: "Account Created",
  TRANSFER_COMPLETED: "Transfer",
  HOLD_AUTHORIZED: "Hold Authorized",
  HOLD_CAPTURED: "Hold Captured",
  HOLD_VOIDED: "Hold Voided",
  DEPOSIT_OPENED: "Deposit Opened",
  DEPOSIT_ACCRUED: "Interest Accrued",
  DEPOSIT_MATURED: "Deposit Matured",
  LOAN_DISBURSED: "Loan Disbursed",
  LOAN_REPAID: "Loan Repaid",
};

function transferDirection(
  payload: ActivityPayload | null,
  accountId: string
): "OUT" | "IN" | "UNKNOWN" {
  if (!payload) return "UNKNOWN";
  if (payload.sourceAccountId === accountId) return "OUT";
  if (payload.destinationAccountId === accountId) return "IN";
  return "UNKNOWN";
}

export function parseActivityPayload(
  payloadJson: string | null
): ActivityPayload | null {
  if (!payloadJson) return null;
  try {
    return JSON.parse(payloadJson) as ActivityPayload;
  } catch {
    return null;
  }
}

export function extractActivityAmount(
  payload: ActivityPayload | null
): { amount: number; currency: string } | null {
  if (!payload) return null;

  const amount =
    typeof payload.amountMinor === "number"
      ? payload.amountMinor
      : typeof payload.amountMajor === "number"
      ? payload.amountMajor
      : null;

  if (amount == null) return null;

  return {
    amount,
    currency: payload.currency?.trim() ? payload.currency : "VND",
  };
}

export function resolveActivityPresentation(
  eventType: string,
  payload: ActivityPayload | null,
  accountId: string
): ActivityPresentationVm {
  if (eventType === "TRANSFER_COMPLETED") {
    const direction = transferDirection(payload, accountId);
    if (direction === "OUT") {
      return {
        label: "Transfer Out",
        amountDirection: "debit",
        impactHint: "Posted transfer debit from this account.",
      };
    }
    if (direction === "IN") {
      return {
        label: "Transfer In",
        amountDirection: "credit",
        impactHint: "Posted transfer credit to this account.",
      };
    }

    return {
      label: "Transfer",
      amountDirection: "neutral",
      impactHint: "Transfer direction is unknown for this account context.",
    };
  }

  if (eventType === "HOLD_AUTHORIZED") {
    return {
      label: "Hold Authorized",
      amountDirection: "neutral",
      impactHint: "Available balance reserved by hold. Posted balance unchanged.",
    };
  }

  if (eventType === "HOLD_CAPTURED") {
    return {
      label: "Hold Captured",
      amountDirection: "debit",
      impactHint: "Hold captured into posted debit.",
    };
  }

  if (eventType === "HOLD_VOIDED") {
    return {
      label: "Hold Voided",
      amountDirection: "credit",
      impactHint: "Hold released; available balance restored.",
    };
  }

  return {
    label: EVENT_TYPE_LABELS[eventType] ?? eventType,
    amountDirection: "neutral",
    impactHint: null,
  };
}
