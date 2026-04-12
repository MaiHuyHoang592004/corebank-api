import type { DemoAccount, DemoActivityItem } from "@/lib/api";

export interface DashboardActivityItemVm extends DemoActivityItem {
  accountId: string;
}

export interface DashboardOutcomeHighlightsVm {
  authorizedCount: number;
  capturedCount: number;
  voidedCount: number;
  transferCount: number;
}

interface AccountActivityItems {
  accountId: string;
  items: DemoActivityItem[];
}

function toEpoch(occurredAt: string | null): number {
  if (!occurredAt) return Number.NEGATIVE_INFINITY;
  const timestamp = Date.parse(occurredAt);
  return Number.isNaN(timestamp) ? Number.NEGATIVE_INFINITY : timestamp;
}

function shouldReplace(
  current: DashboardActivityItemVm,
  incoming: DashboardActivityItemVm
): boolean {
  const currentTs = toEpoch(current.occurredAt);
  const incomingTs = toEpoch(incoming.occurredAt);
  if (incomingTs === currentTs) {
    return incoming.eventId > current.eventId;
  }
  return incomingTs > currentTs;
}

export function aggregateDashboardActivity(
  byAccount: AccountActivityItems[],
  limit: number = 10
): DashboardActivityItemVm[] {
  const deduped = new Map<string, DashboardActivityItemVm>();

  for (const group of byAccount) {
    for (const item of group.items) {
      const candidate: DashboardActivityItemVm = {
        ...item,
        accountId: group.accountId,
      };
      const existing = deduped.get(item.eventId);
      if (!existing || shouldReplace(existing, candidate)) {
        deduped.set(item.eventId, candidate);
      }
    }
  }

  return Array.from(deduped.values())
    .sort((left, right) => {
      const rightTs = toEpoch(right.occurredAt);
      const leftTs = toEpoch(left.occurredAt);
      if (rightTs === leftTs) {
        return right.eventId.localeCompare(left.eventId);
      }
      return rightTs - leftTs;
    })
    .slice(0, limit);
}

export function computeOutcomeHighlights(
  items: DashboardActivityItemVm[]
): DashboardOutcomeHighlightsVm {
  return items.reduce(
    (acc, item) => {
      switch (item.eventType) {
        case "HOLD_AUTHORIZED":
          acc.authorizedCount += 1;
          break;
        case "HOLD_CAPTURED":
          acc.capturedCount += 1;
          break;
        case "HOLD_VOIDED":
          acc.voidedCount += 1;
          break;
        case "TRANSFER_COMPLETED":
          acc.transferCount += 1;
          break;
        default:
          break;
      }
      return acc;
    },
    {
      authorizedCount: 0,
      capturedCount: 0,
      voidedCount: 0,
      transferCount: 0,
    } satisfies DashboardOutcomeHighlightsVm
  );
}

export function createAccountNumberLookup(
  accounts: DemoAccount[]
): Record<string, string> {
  return accounts.reduce<Record<string, string>>((acc, account) => {
    acc[account.accountId] = account.accountNumber;
    return acc;
  }, {});
}
