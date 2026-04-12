"use client"

import Link from "next/link"
import { useMemo, useState } from "react"
import { formatCurrency, formatDateTime, maskAccountNumber } from "@/lib/utils"
import type { DashboardActivityItemVm } from "@/lib/dashboard-activity"

interface RecentActivityProps {
  items: DashboardActivityItemVm[]
  accountNumberById: Record<string, string>
}

const EVENT_TYPE_LABELS: Record<string, string> = {
  ACCOUNT_CREATED: "Account Created",
  TRANSFER_COMPLETED: "Transfer Completed",
  HOLD_AUTHORIZED: "Hold Authorized",
  HOLD_CAPTURED: "Hold Captured",
  HOLD_VOIDED: "Hold Voided",
  DEPOSIT_OPENED: "Deposit Opened",
  DEPOSIT_ACCRUED: "Interest Accrued",
  DEPOSIT_MATURED: "Deposit Matured",
  LOAN_DISBURSED: "Loan Disbursed",
  LOAN_REPAID: "Loan Repaid",
}

const EVENT_TYPE_COLORS: Record<string, string> = {
  TRANSFER_COMPLETED: "text-green-600",
  HOLD_AUTHORIZED: "text-blue-600",
  HOLD_CAPTURED: "text-indigo-600",
  HOLD_VOIDED: "text-gray-500",
  DEPOSIT_OPENED: "text-green-600",
  DEPOSIT_ACCRUED: "text-green-600",
  DEPOSIT_MATURED: "text-indigo-600",
  LOAN_DISBURSED: "text-red-600",
  LOAN_REPAID: "text-green-600",
}

type ActivityFilter = "all" | "payments" | "transfers"

function parsePayloadAmount(payloadJson: string | null): string | null {
  if (!payloadJson) return null
  try {
    const payload = JSON.parse(payloadJson)
    if (payload?.amountMinor != null) {
      return formatCurrency(payload.amountMinor, "VND")
    }
    if (payload?.amountMajor != null) {
      return formatCurrency(payload.amountMajor, "VND")
    }
    return null
  } catch {
    return null
  }
}

function inFilter(item: DashboardActivityItemVm, filter: ActivityFilter): boolean {
  if (filter === "all") return true
  if (filter === "payments") return item.eventType.startsWith("HOLD_")
  return item.eventType === "TRANSFER_COMPLETED"
}

export default function RecentActivity({ items, accountNumberById }: RecentActivityProps) {
  const [filter, setFilter] = useState<ActivityFilter>("all")

  const filteredItems = useMemo(
    () => items.filter((item) => inFilter(item, filter)),
    [items, filter]
  )

  if (items.length === 0) {
    return (
      <div className="rounded-xl border border-gray-200 bg-white p-6 text-center text-gray-500">
        No recent activity.
      </div>
    )
  }

  return (
    <section className="flex w-full flex-col gap-6">
      <header className="flex items-center justify-between">
        <h2 className="text-20 md:text-24 font-semibold text-gray-900">
          Recent Activity
        </h2>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => setFilter("all")}
            className={`rounded-md border px-2.5 py-1 text-xs font-medium ${
              filter === "all"
                ? "border-gray-700 bg-gray-700 text-white"
                : "border-gray-200 bg-white text-gray-700 hover:bg-gray-50"
            }`}
          >
            All
          </button>
          <button
            type="button"
            onClick={() => setFilter("payments")}
            className={`rounded-md border px-2.5 py-1 text-xs font-medium ${
              filter === "payments"
                ? "border-gray-700 bg-gray-700 text-white"
                : "border-gray-200 bg-white text-gray-700 hover:bg-gray-50"
            }`}
          >
            Payments
          </button>
          <button
            type="button"
            onClick={() => setFilter("transfers")}
            className={`rounded-md border px-2.5 py-1 text-xs font-medium ${
              filter === "transfers"
                ? "border-gray-700 bg-gray-700 text-white"
                : "border-gray-200 bg-white text-gray-700 hover:bg-gray-50"
            }`}
          >
            Transfers
          </button>
        </div>
        <Link
          href="/accounts"
          className="view-all-btn"
        >
          View all
        </Link>
      </header>

      <div className="flex flex-col gap-3" data-testid="recent-activity-list">
        {filteredItems.length === 0 && (
          <div className="rounded-lg border border-gray-200 bg-white p-4 text-sm text-gray-500">
            No events in this filter.
          </div>
        )}

        {filteredItems.map((item) => {
          const label = EVENT_TYPE_LABELS[item.eventType] ?? item.eventType
          const colorClass = EVENT_TYPE_COLORS[item.eventType] ?? "text-gray-700"
          const amount = parsePayloadAmount(item.payloadJson)
          const accountNumber = accountNumberById[item.accountId]
          const accountContext = accountNumber
            ? maskAccountNumber(accountNumber)
            : `${item.accountId.slice(0, 8)}...`

          return (
            <div
              key={item.eventId}
              data-testid="recent-activity-row"
              className="flex items-center justify-between rounded-lg border border-gray-200 bg-white p-4"
            >
              <div className="flex flex-col gap-1">
                <p className={`text-14 font-semibold ${colorClass}`}>{label}</p>
                <p className="text-12 text-gray-500">
                  {formatDateTime(item.occurredAt ?? null)}
                  {item.actor && ` · by ${item.actor}`}
                </p>
                <p className="text-11 text-gray-400">Account {accountContext}</p>
              </div>
              {amount && (
                <p className={`text-14 font-semibold ${colorClass}`}>{amount}</p>
              )}
            </div>
          )
        })}
      </div>
    </section>
  )
}
