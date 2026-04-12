export type DemoSetupState =
  | "backend_unreachable"
  | "setup_creds_missing"
  | "seed_required"
  | "ready";

interface ResolveSetupStateInput {
  backendReachable: boolean;
  setupConfigured: boolean;
  hasSeedData: boolean;
}

export function resolveDemoSetupState({
  backendReachable,
  setupConfigured,
  hasSeedData,
}: ResolveSetupStateInput): DemoSetupState {
  if (!backendReachable) {
    return "backend_unreachable";
  }

  if (!setupConfigured) {
    return "setup_creds_missing";
  }

  if (!hasSeedData) {
    return "seed_required";
  }

  return "ready";
}

export interface SetupResultSummary {
  initializedAt: string | null;
  sourceAccountId: string | null;
  destinationAccountId: string | null;
  paymentAmountMinor: number | null;
}

interface SetupResponsePayload {
  initializedAt?: unknown;
  accountIds?: Record<string, unknown>;
  sampleAmountsMinor?: Record<string, unknown>;
}

export function parseSetupResultSummary(payload: unknown): SetupResultSummary | null {
  if (!payload || typeof payload !== "object") {
    return null;
  }

  const data = payload as SetupResponsePayload;
  const accountIds = data.accountIds ?? {};
  const sampleAmountsMinor = data.sampleAmountsMinor ?? {};

  return {
    initializedAt: typeof data.initializedAt === "string" ? data.initializedAt : null,
    sourceAccountId:
      typeof accountIds.sourceAccountId === "string"
        ? accountIds.sourceAccountId
        : null,
    destinationAccountId:
      typeof accountIds.destinationAccountId === "string"
        ? accountIds.destinationAccountId
        : null,
    paymentAmountMinor:
      typeof sampleAmountsMinor.paymentAmountMinor === "number"
        ? sampleAmountsMinor.paymentAmountMinor
        : null,
  };
}

export function shortenId(value: string | null | undefined, chars: number = 8): string {
  if (!value) {
    return "—";
  }
  return `${value.slice(0, chars)}...`;
}
